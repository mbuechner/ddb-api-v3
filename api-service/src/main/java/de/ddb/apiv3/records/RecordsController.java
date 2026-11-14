/* 
 *  Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 *  This code was made with help from OpenAI GTP-6.1 Sol Ultra.
 */

package de.ddb.apiv3.records;

import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import de.ddb.apiv3.storage.StorageException;
import de.ddb.apiv3.rdf.RdfCodec;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.apache.jena.riot.RDFLanguages;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** RDF/XML uploads, JSON-LD by default, Jelly in S3. One route owns one object key. */
@RestController
public final class RecordsController {

    private static final String RECORD_PATH = "/3/records/{recordId}";
    private static final String DDB_ID = "[A-Z2-7]{32}";
    private static final java.util.regex.Pattern RECORD_ID = java.util.regex.Pattern.compile(DDB_ID);
    private static final java.util.regex.Pattern STRONG_ETAG = java.util.regex.Pattern.compile(
            "\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"");
    private final RecordUploadService uploadService;
    private final S3RdfDocumentStore repository;
    private final RdfCodec rdfCodec;

    public RecordsController(RecordUploadService uploadService, S3RdfDocumentStore repository, RdfCodec rdfCodec) {
        this.uploadService = uploadService;
        this.repository = repository;
        this.rdfCodec = rdfCodec;
    }

    @PostMapping(path = RECORD_PATH, consumes = "application/rdf+xml")
    public ResponseEntity<Void> createRecord(
            @PathVariable @Pattern(regexp = DDB_ID) String recordId,
            @RequestBody InputStreamResource body, UriComponentsBuilder requestUri) {
        String etag = uploadService.create(recordId, body, requestUri.build(true).toUri());
        return ResponseEntity.created(URI.create("/3/records/" + recordId))
                .eTag(etag)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .build();
    }

    @PutMapping(path = RECORD_PATH, consumes = "application/rdf+xml")
    public ResponseEntity<Void> putRecord(
            @PathVariable @Pattern(regexp = DDB_ID) String recordId,
            @RequestBody InputStreamResource body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, UriComponentsBuilder requestUri) {
        String etag = uploadService.update(recordId, body, ifMatch, requestUri.build(true).toUri());
        // PUT transforms the representation, so RFC 9110 forbids a response ETag here.
        return ResponseEntity.noContent()
                .header("X-Record-ETag", etag)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .build();
    }

    @GetMapping(path = RECORD_PATH)
    public ResponseEntity<byte[]> getRecord(
            @PathVariable String recordId,
            @RequestHeader(name = HttpHeaders.ACCEPT, defaultValue = "application/ld+json") String accept,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        // An ID outside our namespace cannot identify a record; write validation stays strict.
        if (!RECORD_ID.matcher(recordId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The record does not exist");
        }
        MediaType outputType;
        try {
            outputType = rdfCodec.outputMediaType(MediaType.parseMediaTypes(accept));
        } catch (InvalidMediaTypeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept is not a valid media type list", exception);
        }
        var stored = repository.read(recordId, "record")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The record does not exist"));
        String recordEtag = stored.response().eTag();
        if (recordEtag == null || !STRONG_ETAG.matcher(recordEtag).matches()
                || !RdfCodec.JELLY_CONTENT_TYPE.equals(stored.response().contentType())) {
            throw new StorageException("The stored record has invalid Jelly metadata");
        }

        // Formats and gzip change bytes. This ETag compares RDF semantics; the strong one is for PUT.
        String responseEtag = "W/" + recordEtag;
        boolean notModified = matches(ifNoneMatch, recordEtag);
        var response = ResponseEntity.status(notModified
                        ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(responseEtag)
                .header("X-Record-ETag", recordEtag)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .varyBy(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_ENCODING);
        if (notModified) {
            return response.build();
        }
        return response.contentType(outputType)
                .body(rdfCodec.fromJelly(stored.asByteArrayUnsafe(),
                        RDFLanguages.contentTypeToLang(outputType.getType() + "/" + outputType.getSubtype())));
    }

    private static boolean matches(String ifNoneMatch, String recordEtag) {
        if (ifNoneMatch == null) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        try {
            return ETag.parse(ifNoneMatch).stream()
                    .anyMatch(tag -> tag.compare(ETag.create(recordEtag), false));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
