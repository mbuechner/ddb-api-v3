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
import de.ddb.apiv3.generated.model.AdministrativeRecord;
import de.ddb.apiv3.generated.model.RecordWriteMetadata;
import de.ddb.apiv3.record.StoredRecord;
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
import org.springframework.http.CacheControl;
import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handwritten HTTP adapter for the generated Records API.
 *
 * <p>The generated controller owns mappings and validation. Endpoint-specific behavior lives in
 * the adjacent {@code metadata} and {@code write} packages, keeping this adapter limited to HTTP
 * headers and conditional request handling.</p>
 */
@Service
public final class RecordsApiDelegateImpl implements RecordsApiDelegate {

    private final RecordMetadataService metadataService;
    private final DcatRecordMetadataMapper metadataMapper;
    private final RecordUploadService uploadService;
    private final HttpServletRequest request;

    public RecordsApiDelegateImpl(
            RecordMetadataService metadataService,
            DcatRecordMetadataMapper metadataMapper,
            RecordUploadService uploadService,
            HttpServletRequest request) {
        this.metadataService = metadataService;
        this.metadataMapper = metadataMapper;
        this.uploadService = uploadService;
        this.request = request;
    }

    /** Validates and atomically creates an uploaded RDF/XML record. */
    @Override
    public ResponseEntity<AdministrativeRecord> createRecord(
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
                metadataMapper.toAdministrativeRecord(record.metadata()), headers, HttpStatus.CREATED);
    }

    /** Updates a record without changing its externally assigned identifier. */
    @Override
    public ResponseEntity<AdministrativeRecord> putRecord(
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
                metadataMapper.toAdministrativeRecord(record.metadata()),
                headers,
                HttpStatus.OK);
    }

    /** Returns administrative JSON-LD metadata and applies ETag and Last-Modified preconditions. */
    @Override
    public ResponseEntity<AdministrativeRecord> getRecord(
            String recordId,
            String ifNoneMatch,
            String ifModifiedSince,
            String acceptEncoding) {
        StoredRecord record = metadataService.getRecord(recordId);
        HttpHeaders headers = metadataHeaders(record);

        if (notModified(record.etag(), record.lastModified())) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }

        return new ResponseEntity<>(metadataMapper.toAdministrativeRecord(record.metadata()), headers, HttpStatus.OK);
    }

    private static HttpHeaders metadataHeaders(StoredRecord record) {
        HttpHeaders headers = commonHeaders(record.etag(), record.lastModified());
        headers.setVary(List.of(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_ENCODING));
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
