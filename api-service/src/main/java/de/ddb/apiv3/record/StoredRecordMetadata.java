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
package de.ddb.apiv3.record;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Host-neutral metadata persisted with a record.
 *
 * <p>This domain value deliberately contains identifiers and facts only, never deployment URLs or
 * a JSON-LD context. The HTTP adapter can therefore create API and public DDB resource IRIs
 * from configuration without rewriting Cassandra data when a service moves.</p>
 */
public record StoredRecordMetadata(
        String recordId,
        List<String> datasetIds,
        String providerId,
        String aggregatorId,
        String sourceId,
        OffsetDateTime createdAt,
        OffsetDateTime modifiedAt,
        OffsetDateTime deletedAt,
        URI deletionReason,
        String deletionComment,
        PublicationStatus publicationStatus,
        String profileVersion,
        String mediaType,
        Long contentLength,
        String sha256,
        String sourceMediaType,
        String sourceFormat,
        URI sourceFormatUri,
        String sourceFormatVersion,
        String sourceProfile,
        URI sourceProfileUri,
        Long sourceLength,
        String sourceSha256,
        ValidationStatus validationStatus) {

    /**
     * Whether this state is a tombstone rather than an RDF record that may be served.
     *
     * <p>A tombstone deliberately keeps the stable record identity and administrative change
     * metadata while making the retained content unavailable through the read API.</p>
     */
    public boolean isTombstone() {
        return publicationStatus == PublicationStatus.DELETED;
    }

    /** Lifecycle values mapped to the EU Dataset Status vocabulary through {@code adms:status}. */
    public enum PublicationStatus {
        DRAFT,
        PUBLISHED,
        WITHDRAWN,
        DELETED
    }

    /** Validation states mapped to standard EARL test outcomes at the HTTP boundary. */
    public enum ValidationStatus {
        VALID,
        VALID_WITH_WARNINGS,
        INVALID,
        NOT_VALIDATED
    }
}
