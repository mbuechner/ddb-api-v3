/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.documents;

import de.ddb.apiv3.config.ApiProperties;
import de.ddb.apiv3.config.RequestTiming;
import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.storage.StorageException;
import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.jena.riot.RDFLanguages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

/** Shared HTTP/RDF lifecycle. Controllers declare routes; their type selects storage and validation. */
@Service
public final class RdfDocumentService {
    public static final String DDB_ID = "[A-Z2-7]{32}";
    private static final Pattern IDENTIFIER = Pattern.compile(DDB_ID);
    private static final Pattern STRONG_ETAG = Pattern.compile("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"");
    private static final String ENTITY_TAG = "(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"";
    private static final Pattern ENTITY_TAG_LIST = Pattern.compile(
            ENTITY_TAG + "(?:[ \\t]*,[ \\t]*" + ENTITY_TAG + ")*");
    private final S3RdfDocumentStore repository;
    private final RdfCodec rdfCodec;
    private final ApiProperties apiProperties;
    private final int maxDocumentBytes;

    public RdfDocumentService(S3RdfDocumentStore repository, RdfCodec rdfCodec, ApiProperties apiProperties,
            @Value("${ddb.api.max-document-size:1MB}") DataSize maxDocumentSize) {
        long bytes = maxDocumentSize.toBytes();
        if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The RDF document size limit must be positive and below 2 GB");
        }
        this.repository = repository;
        this.rdfCodec = rdfCodec;
        this.apiProperties = apiProperties;
        this.maxDocumentBytes = (int) bytes;
    }

    public ResponseEntity<Void> create(RdfDocumentType type, String id, InputStreamSource content, URI requestUri) {
        byte[] jelly = validatedJelly(type, id, content, requestUri);
        String etag = repository.create(id, type.documentName(), jelly)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The RDF " + type.documentName() + " document already exists"));
        return ResponseEntity.created(URI.create(type.versionedPath(id))).eTag(etag)
                .header(RequestTiming.HEADER, RequestTiming.requestId()).build();
    }

    public ResponseEntity<Void> update(RdfDocumentType type, String id, InputStreamSource content,
            String ifMatch, URI requestUri) {
        String condition = strongIfMatch(ifMatch);
        byte[] jelly = validatedJelly(type, id, content, requestUri);
        String etag = repository.replace(id, type.documentName(), jelly, condition)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "If-Match does not match an existing " + type.documentName() + " document"));
        // Transformed PUT bodies cannot use a response ETag (RFC 9110); expose the storage revision separately.
        return ResponseEntity.noContent().header(type.revisionHeader(), etag)
                .header(RequestTiming.HEADER, RequestTiming.requestId()).build();
    }

    public ResponseEntity<byte[]> get(RdfDocumentType type, String id, String accept, String ifNoneMatch) {
        if (!IDENTIFIER.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The RDF document does not exist");
        }
        MediaType outputType;
        try {
            outputType = rdfCodec.outputMediaType(MediaType.parseMediaTypes(accept));
        } catch (InvalidMediaTypeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept is not a valid media type list", exception);
        }
        var stored = repository.read(id, type.documentName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The RDF document does not exist"));
        String etag = stored.response().eTag();
        if (etag == null || !STRONG_ETAG.matcher(etag).matches()
                || !RdfCodec.JELLY_CONTENT_TYPE.equals(stored.response().contentType())) {
            throw new StorageException("The stored RDF document has invalid Jelly metadata");
        }
        boolean notModified = matches(ifNoneMatch, etag);
        // Different serializations/gzip change bytes: GET's ETag is semantic and weak, unlike the S3 revision.
        var response = ResponseEntity.status(notModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag("W/" + etag).header(type.revisionHeader(), etag)
                .header(RequestTiming.HEADER, RequestTiming.requestId())
                .varyBy(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_ENCODING);
        if (notModified) {
            return response.build();
        }
        // Stored Jelly is checked for integrity, but endpoint SHACL is a write policy, never a GET cost.
        return response.contentType(outputType).body(rdfCodec.fromJelly(stored.asByteArrayUnsafe(),
                RDFLanguages.contentTypeToLang(outputType.getType() + "/" + outputType.getSubtype())));
    }

    private byte[] validatedJelly(RdfDocumentType type, String id, InputStreamSource content, URI requestUri) {
        byte[] rdf;
        try (var timing = RequestTiming.start("upload_read"); InputStream stream = content.getInputStream()) {
            rdf = stream.readNBytes(maxDocumentBytes + 1);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The RDF request body could not be read", exception);
        }
        if (rdf.length > maxDocumentBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The RDF document exceeds the size limit");
        }
        String graphIri = apiProperties.resolveOrigin(requestUri) + "/" + type.collection() + "/" + id;
        // One parse, one request-local graph: SHACL runs before Jelly encoding and before any S3 write.
        return rdfCodec.toJelly(rdf, graphIri, type.validator());
    }

    private static String strongIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        }
        String condition = ifMatch.trim();
        if ("*".equals(condition)) {
            return condition;
        }
        // ETag.parse is forgiving; write preconditions must consume the entire header.
        if (!ENTITY_TAG_LIST.matcher(condition).matches()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid ETag list");
        }
        try {
            String strongTags = ETag.parse(condition).stream().filter(tag -> !tag.weak())
                    .map(ETag::formattedTag).collect(Collectors.joining(", "));
            if (!strongTags.isEmpty()) {
                return strongTags;
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid strong ETag");
        }
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match requires a strong ETag");
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        try {
            return ETag.parse(ifNoneMatch).stream().anyMatch(tag -> tag.compare(ETag.create(etag), false));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
