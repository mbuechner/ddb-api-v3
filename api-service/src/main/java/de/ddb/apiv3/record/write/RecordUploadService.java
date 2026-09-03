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
package de.ddb.apiv3.record.write;

import de.ddb.apiv3.generated.model.RecordWriteMetadata;
import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.StoredRecordMetadata;
import de.ddb.apiv3.record.persistence.RecordRepository;
import de.ddb.apiv3.record.persistence.StoredRecordMetadataJsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ETag;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinates validation and atomic persistence of uploaded records.
 *
 * <p>All request parts are read and the complete RDF/XML document is parsed before a repository
 * mutation is attempted. POST creates the externally supplied, globally unique record ID exactly
 * once. PUT updates an existing record with a matching strong {@code If-Match} ETag. The service
 * never derives or replaces an ID and does not maintain record versions.</p>
 *
 * <p>The stored RDF and optional source each receive their own SHA-256 content hash. The record
 * ETag is a SHA-256 hash of the complete stored metadata state, which includes both content
 * hashes, lengths and lifecycle data.</p>
 */
@Service
public final class RecordUploadService {

    /** Canonical storage format defined by the write API contract. */
    private static final MediaType RDF_XML = MediaType.parseMediaType("application/rdf+xml");

    private final RecordRepository repository;
    private final RdfXmlValidator rdfXmlValidator;
    private final StoredRecordMetadataJsonCodec metadataCodec;

    public RecordUploadService(
            RecordRepository repository,
            RdfXmlValidator rdfXmlValidator,
            StoredRecordMetadataJsonCodec metadataCodec) {
        this.repository = repository;
        this.rdfXmlValidator = rdfXmlValidator;
        this.metadataCodec = metadataCodec;
    }

    /**
     * Creates a new stable record after validating the complete upload.
     *
     * @param requestMetadata administrative and source metadata from the multipart request
     * @param content mandatory canonical RDF/XML part
     * @param source optional original source part retained without transformation
     * @return the newly stored record
     * @throws InvalidRdfException if Jena cannot parse {@code content} as RDF/XML
     * @throws RecordAlreadyExistsException if the supplied stable identifier already exists
     */
    public StoredRecord create(
            RecordWriteMetadata requestMetadata,
            MultipartFile content,
            MultipartFile source) {
        PreparedUpload upload = prepare(requestMetadata.getRecordId(), requestMetadata, content, source);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        StoredRecord record = record(upload, now, now);
        if (!repository.create(record, upload.rdfXml(), upload.source())) {
            throw new RecordAlreadyExistsException(upload.recordId());
        }
        return record;
    }

    /**
     * Updates an existing record using an HTTP precondition for concurrency control.
     *
     * <p>The path identifier must equal the externally supplied identifier in the metadata.
     * The current strong ETag is required in {@code If-Match}. The repository checks it atomically
     * a second time to close the race between lookup and update.</p>
     *
     * @return the updated state stored under the unchanged record identifier
     * @throws InvalidRdfException before preconditions or persistence if the RDF is invalid
     */
    public StoredRecord update(
            String pathRecordId,
            RecordWriteMetadata requestMetadata,
            MultipartFile content,
            MultipartFile source,
            String ifMatch) {
        if (!pathRecordId.equals(requestMetadata.getRecordId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The record ID in the metadata must match the path record ID");
        }
        PreparedUpload upload = prepare(pathRecordId, requestMetadata, content, source);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        StoredRecord current = repository.findById(pathRecordId)
                .orElseThrow(() -> new NoSuchElementException("Record '" + pathRecordId + "' was not found"));
        if (!matchesIfMatch(ifMatch, current.etag())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match does not match");
        }
        StoredRecord replacement = record(upload, current.metadata().createdAt(), now);
        if (!repository.replace(replacement, upload.rdfXml(), upload.source(), current.etag())) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "The record changed while it was being replaced");
        }
        return replacement;
    }

    private PreparedUpload prepare(
            String recordId,
            RecordWriteMetadata requestMetadata,
            MultipartFile content,
            MultipartFile source) {
        requireRdfXml(content);
        validateSourceMetadata(requestMetadata, source);

        byte[] rdfXml = read(content, "content");
        byte[] sourceBytes = source == null ? null : read(source, "source");
        // Validation happens before every create or replacement repository call. The stable URN
        // gives relative RDF identifiers a deterministic base independent of the deployment URL.
        rdfXmlValidator.validate(rdfXml, "urn:ddb:record:" + recordId);
        return new PreparedUpload(recordId, requestMetadata, rdfXml, sourceBytes);
    }

    /** Builds immutable storage metadata and its strong state ETag. */
    private StoredRecord record(
            PreparedUpload upload,
            OffsetDateTime createdAt,
            OffsetDateTime modifiedAt) {
        RecordWriteMetadata requestMetadata = upload.metadata();
        StoredRecordMetadata metadata = new StoredRecordMetadata(
                upload.recordId(),
                requestMetadata.getDatasetIds().stream().sorted().toList(),
                requestMetadata.getProviderId(),
                requestMetadata.getAggregatorId(),
                requestMetadata.getSourceId(),
                createdAt,
                modifiedAt,
                null,
                null,
                null,
                publicationStatus(requestMetadata.getPublicationStatus()),
                requestMetadata.getProfileVersion(),
                RDF_XML.toString(),
                (long) upload.rdfXml().length,
                Hashing.sha256(upload.rdfXml()),
                requestMetadata.getSourceMediaType() == null
                        ? null
                        : requestMetadata.getSourceMediaType().getValue(),
                requestMetadata.getSourceFormat(),
                requestMetadata.getSourceFormatUri(),
                requestMetadata.getSourceFormatVersion(),
                requestMetadata.getSourceProfile(),
                uri(requestMetadata.getSourceProfileUri()),
                upload.source() == null ? null : (long) upload.source().length,
                upload.source() == null ? null : Hashing.sha256(upload.source()),
                StoredRecordMetadata.ValidationStatus.NOT_VALIDATED);
        // Hashing the serialized immutable metadata gives all storage adapters the same opaque,
        // strong validator without coupling the ETag to a database implementation.
        String stateHash = Hashing.sha256(metadataCodec.encode(metadata).getBytes(StandardCharsets.UTF_8));
        String etag = '"' + "sha256-" + stateHash + '"';
        return new StoredRecord(metadata, etag, modifiedAt);
    }

    private static void requireRdfXml(MultipartFile content) {
        MediaType contentType;
        try {
            contentType = content.getContentType() == null
                    ? null
                    : MediaType.parseMediaType(content.getContentType());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The content part must use application/rdf+xml", exception);
        }
        if (contentType == null || !RDF_XML.equalsTypeAndSubtype(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The content part must use application/rdf+xml");
        }
    }

    private static void validateSourceMetadata(RecordWriteMetadata metadata, MultipartFile source) {
        boolean hasSourceMetadata = metadata.getSourceMediaType() != null
                || metadata.getSourceFormat() != null
                || metadata.getSourceFormatUri() != null
                || metadata.getSourceFormatVersion() != null
                || metadata.getSourceProfile() != null
                || metadata.getSourceProfileUri() != null;
        if (source == null && hasSourceMetadata) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Source metadata requires a source part");
        }
        if (source != null
                && (metadata.getSourceMediaType() == null
                || metadata.getSourceFormat() == null
                || metadata.getSourceFormatUri() == null
                || metadata.getSourceFormatVersion() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The source part requires complete source metadata");
        }
        if ((metadata.getSourceProfile() == null) != (metadata.getSourceProfileUri() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "sourceProfile and sourceProfileUri must occur together");
        }
    }

    private static byte[] read(MultipartFile part, String name) {
        try {
            return part.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The " + name + " part could not be read", exception);
        }
    }

    private static StoredRecordMetadata.PublicationStatus publicationStatus(
            RecordWriteMetadata.PublicationStatusEnum status) {
        return switch (status) {
            case DRAFT -> StoredRecordMetadata.PublicationStatus.DRAFT;
            case PUBLISHED -> StoredRecordMetadata.PublicationStatus.PUBLISHED;
        };
    }

    private static URI uri(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "sourceProfileUri is not a valid URI reference", exception);
        }
    }

    private static boolean matchesIfMatch(String ifMatch, String currentEtag) {
        if (ifMatch == null) {
            return false;
        }
        try {
            ETag current = ETag.create(currentEtag);
            return ETag.parse(ifMatch).stream()
                    .anyMatch(candidate -> candidate.isWildcard() || candidate.compare(current, true));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Fully materialized and validated request, ready for a single repository mutation. */
    private record PreparedUpload(
            String recordId,
            RecordWriteMetadata metadata,
            byte[] rdfXml,
            byte[] source) {
    }

}
