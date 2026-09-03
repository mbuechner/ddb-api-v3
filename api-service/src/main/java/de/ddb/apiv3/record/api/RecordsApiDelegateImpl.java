/*
 * Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This code was generated with assistance from OpenAI ChatGPT.
 */
package de.ddb.apiv3.record.api;

import de.ddb.apiv3.generated.api.RecordsApiDelegate;
import de.ddb.apiv3.generated.model.DcatCatalogRecord;
import de.ddb.apiv3.generated.model.RecordWriteMetadata;
import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.content.RecordContentService;
import de.ddb.apiv3.record.content.RecordContentService.Representation;
import de.ddb.apiv3.record.metadata.DcatRecordMetadataMapper;
import de.ddb.apiv3.record.metadata.RecordMetadataService;
import de.ddb.apiv3.record.write.RecordUploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handwritten HTTP adapter for the generated Records API.
 *
 * <p>The generated controller owns mappings and validation. Endpoint-specific behavior lives in
 * the adjacent {@code metadata} and {@code content} packages, keeping this adapter limited to HTTP
 * headers and conditional request handling.</p>
 */
@Service
public final class RecordsApiDelegateImpl implements RecordsApiDelegate {

    private final RecordMetadataService metadataService;
    private final DcatRecordMetadataMapper metadataMapper;
    private final RecordContentService contentService;
    private final RecordUploadService uploadService;
    private final HttpServletRequest request;

    public RecordsApiDelegateImpl(
            RecordMetadataService metadataService,
            DcatRecordMetadataMapper metadataMapper,
            RecordContentService contentService,
            RecordUploadService uploadService,
            HttpServletRequest request) {
        this.metadataService = metadataService;
        this.metadataMapper = metadataMapper;
        this.contentService = contentService;
        this.uploadService = uploadService;
        this.request = request;
    }

    /** Validates and atomically creates an uploaded RDF/XML record. */
    @Override
    public ResponseEntity<DcatCatalogRecord> createRecord(
            RecordWriteMetadata metadata,
            MultipartFile content,
            UUID idempotencyKey,
            String contentEncoding,
            String acceptEncoding,
            MultipartFile source) {
        StoredRecord record = uploadService.create(metadata, content, source);
        HttpHeaders headers = metadataHeaders(record);
        headers.setLocation(URI.create("/3/records/" + record.metadata().recordId()));
        return new ResponseEntity<>(
                metadataMapper.toDcatCatalogRecord(record.metadata()), headers, HttpStatus.CREATED);
    }

    /** Updates a record without changing its externally assigned identifier. */
    @Override
    public ResponseEntity<DcatCatalogRecord> putRecord(
            String recordId,
            String ifMatch,
            RecordWriteMetadata metadata,
            MultipartFile content,
            UUID idempotencyKey,
            String contentEncoding,
            String acceptEncoding,
            MultipartFile source) {
        StoredRecord record = uploadService.update(recordId, metadata, content, source, ifMatch);
        HttpHeaders headers = metadataHeaders(record);
        return new ResponseEntity<>(
                metadataMapper.toDcatCatalogRecord(record.metadata()),
                headers,
                HttpStatus.OK);
    }

    /** Returns DCAT JSON-LD metadata and applies ETag and Last-Modified preconditions. */
    @Override
    public ResponseEntity<DcatCatalogRecord> getRecord(
            String recordId,
            String ifNoneMatch,
            String ifModifiedSince,
            String acceptEncoding) {
        StoredRecord record = metadataService.getRecord(recordId);
        HttpHeaders headers = metadataHeaders(record);

        if (notModified(record.etag(), record.lastModified())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }

        return new ResponseEntity<>(metadataMapper.toDcatCatalogRecord(record.metadata()), headers, HttpStatus.OK);
    }

    /**
     * Returns stored RDF/XML byte-for-byte or the Jena serialization selected by {@code Accept}.
     */
    @Override
    public ResponseEntity<Resource> getRecordContent(
            String recordId,
            String ifNoneMatch,
            String ifModifiedSince,
            String acceptEncoding) {
        boolean headRequest = "HEAD".equals(request.getMethod());
        Representation representation = contentService.getRepresentation(recordId, acceptedMediaTypes());
        HttpHeaders headers = contentHeaders(representation);
        if (notModified(representation.etag(), representation.lastModified())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        Resource body = headRequest ? null : new ByteArrayResource(representation.body());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** Returns the same representation headers as GET without invoking a derived serializer. */
    @Override
    public ResponseEntity<Void> headRecordContent(
            String recordId,
            String ifNoneMatch,
            String ifModifiedSince,
            String acceptEncoding) {
        Representation representation = contentService.getRepresentation(recordId, acceptedMediaTypes());
        HttpHeaders headers = contentHeaders(representation);
        HttpStatus status = notModified(representation.etag(), representation.lastModified())
                ? HttpStatus.NOT_MODIFIED
                : HttpStatus.OK;
        return new ResponseEntity<>(null, headers, status);
    }

    private List<MediaType> acceptedMediaTypes() {
        return MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT));
    }

    private static HttpHeaders metadataHeaders(StoredRecord record) {
        HttpHeaders headers = commonHeaders(record.etag(), record.lastModified());
        headers.setVary(List.of(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_ENCODING));
        return headers;
    }

    private static HttpHeaders contentHeaders(Representation representation) {
        HttpHeaders headers = commonHeaders(representation.etag(), representation.lastModified());
        headers.setContentType(representation.mediaType());
        headers.setVary(List.of(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_ENCODING));
        headers.set("X-Content-SHA256", representation.contentSha256());
        if (representation.contentLength() != null) {
            headers.setContentLength(representation.contentLength());
        }
        return headers;
    }

    private static HttpHeaders commonHeaders(String etag, OffsetDateTime lastModified) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setLastModified(lastModified.toInstant());
        headers.setCacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic());
        headers.set("X-Request-Id", UUID.randomUUID().toString());
        return headers;
    }

    private boolean notModified(String etag, OffsetDateTime lastModified) {
        List<String> ifNoneMatch = Collections.list(request.getHeaders(HttpHeaders.IF_NONE_MATCH));
        if (!ifNoneMatch.isEmpty()) {
            try {
                ETag current = ETag.create(etag);
                return ifNoneMatch.stream()
                        .flatMap(value -> ETag.parse(value).stream())
                        .anyMatch(candidate -> candidate.isWildcard() || candidate.compare(current, false));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        try {
            long ifModifiedSince = request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
            long lastModifiedSeconds = lastModified.toInstant().toEpochMilli() / 1000;
            return ifModifiedSince >= 0 && lastModifiedSeconds <= ifModifiedSince / 1000;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
