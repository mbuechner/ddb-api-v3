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

import de.ddb.apiv3.config.ApiProperties;
import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import de.ddb.apiv3.rdf.RdfCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ETag;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

/** Bound the RDF/XML body, let Jena validate it, then store one named graph as Jelly. */
@Service
public final class RecordUploadService {

    private static final String DOCUMENT_NAME = "record";
    private static final String ENTITY_TAG = "(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"";
    private static final Pattern ENTITY_TAG_LIST = Pattern.compile(
            ENTITY_TAG + "(?:[ \\t]*,[ \\t]*" + ENTITY_TAG + ")*");

    private final S3RdfDocumentStore repository;
    private final RdfCodec rdfCodec;
    private final int maxDocumentBytes;
    private final ApiProperties apiProperties;

    public RecordUploadService(
            S3RdfDocumentStore repository,
            RdfCodec rdfCodec,
            ApiProperties apiProperties,
            @Value("${ddb.api.max-document-size:1MB}") DataSize maxDocumentSize) {
        long bytes = maxDocumentSize.toBytes();
        if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The RDF document size limit must be positive and below 2 GB");
        }
        this.repository = repository;
        this.rdfCodec = rdfCodec;
        this.maxDocumentBytes = (int) bytes;
        this.apiProperties = apiProperties;
    }

    /** A DDB ID is a namespace: creating its record leaves its item alone. */
    public String create(String recordId, InputStreamSource content, URI requestUri) {
        byte[] jelly = validatedContent(recordId, content, requestUri);
        return repository.create(recordId, DOCUMENT_NAME, jelly)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "RDF document '" + recordId + "/record' already exists"));
    }

    /** Replaces an existing record document only while its S3 ETag still matches. */
    public String update(String recordId, InputStreamSource content, String ifMatch, URI requestUri) {
        String condition = strongIfMatch(ifMatch);
        byte[] jelly = validatedContent(recordId, content, requestUri);
        return repository.replace(recordId, DOCUMENT_NAME, jelly, condition)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PRECONDITION_FAILED, "If-Match does not match an existing record document"));
    }

    private byte[] validatedContent(String recordId, InputStreamSource content, URI requestUri) {
        byte[] rdf;
        try (InputStream stream = content.getInputStream()) {
            // Limit decompressed input, including requests without a Content-Length header.
            rdf = stream.readNBytes(maxDocumentBytes + 1);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The RDF request body could not be read", exception);
        }
        if (rdf.length > maxDocumentBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The RDF document exceeds the size limit");
        }
        return rdfCodec.toJelly(rdf, apiProperties.resolveOrigin(requestUri) + "/records/" + recordId);
    }

    private static String strongIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        }
        String condition = ifMatch.trim();
        if ("*".equals(condition)) {
            return condition;
        }
        // ETag.parse is forgiving. Write preconditions must consume the entire header.
        if (!ENTITY_TAG_LIST.matcher(condition).matches()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid ETag list");
        }
        try {
            String strongTags = ETag.parse(condition).stream()
                    .filter(tag -> !tag.weak()).map(tag -> tag.formattedTag()).collect(Collectors.joining(", "));
            if (!strongTags.isEmpty()) {
                return strongTags;
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid strong ETag");
        }
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match requires a strong ETag");
    }
}
