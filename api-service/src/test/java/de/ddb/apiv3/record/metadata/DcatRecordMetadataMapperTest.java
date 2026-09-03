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
package de.ddb.apiv3.record.metadata;

import de.ddb.apiv3.record.StoredRecordMetadata;

import de.ddb.apiv3.generated.model.DcatCatalogRecord;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests the boundary between host-neutral stored metadata and public DCAT JSON-LD values.
 *
 * <p>The suite documents which identifiers use the API origin, which use the stable resource
 * origin, and how lifecycle, tombstone and EARL validation states map to controlled vocabularies.
 * It intentionally tests the mapper without serialization; JSON-LD field names remain part of the
 * generated OpenAPI contract tests.</p>
 */
class DcatRecordMetadataMapperTest {

    private static final String RECORD_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T12:00:00Z");
    private final DcatRecordMetadataMapper mapper = new DcatRecordMetadataMapper(
            "https://metadata.example/api", "https://resources.example/catalog");

    @Test
    void separatesApiMetadataFromStableDdbResourceIdentifiers() {
        DcatCatalogRecord result = mapper.toDcatCatalogRecord(metadata(
                StoredRecordMetadata.PublicationStatus.PUBLISHED,
                StoredRecordMetadata.ValidationStatus.VALID));

        assertThat(result.getAtId())
                .isEqualTo("https://metadata.example/api/3/records/" + RECORD_ID + "#metadata");
        assertThat(result.getAtContext())
                .isEqualTo("https://metadata.example/api/contexts/record-metadata-v1.jsonld");
        assertThat(result.getPrimaryTopic().getAtId())
                .isEqualTo("https://resources.example/catalog/item/" + RECORD_ID);
        assertThat(result.getPrimaryTopic().getDatasets()).containsExactly(
                "https://resources.example/catalog/dataset/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                "https://resources.example/catalog/dataset/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE");
        assertThat(result.getPrimaryTopic().getProvider())
                .isEqualTo(
                        "https://resources.example/catalog/organization/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        assertThat(result.getPrimaryTopic().getAggregator())
                .isEqualTo(
                        "https://resources.example/catalog/organization/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }

    @ParameterizedTest
    @CsvSource({
        "DRAFT, DEVELOP",
        "PUBLISHED, COMPLETED",
        "WITHDRAWN, WITHDRAWN",
        "DELETED, WITHDRAWN"
    })
    void mapsLifecycleToTheEuDatasetStatusVocabulary(
            StoredRecordMetadata.PublicationStatus status, String expectedConcept) {
        DcatCatalogRecord result =
                mapper.toDcatCatalogRecord(metadata(status, StoredRecordMetadata.ValidationStatus.VALID));

        assertThat(result.getPrimaryTopic().getPublicationStatus().getValue().toString())
                .isEqualTo("http://publications.europa.eu/resource/authority/dataset-status/" + expectedConcept);
        if (status == StoredRecordMetadata.PublicationStatus.DELETED) {
            assertThat(result.getPrimaryTopic().getInvalidatedAtTime()).isEqualTo(NOW);
            assertThat(result.getPrimaryTopic().getDeletionReason())
                    .isEqualTo(URI.create("https://example.org/deletion-reasons/provider-request"));
            assertThat(result.getPrimaryTopic().getDeletionComment())
                    .isEqualTo("Vom Datenpartner zurueckgezogen.");
        } else {
            assertThat(result.getPrimaryTopic().getInvalidatedAtTime()).isNull();
            assertThat(result.getPrimaryTopic().getDeletionReason()).isNull();
            assertThat(result.getPrimaryTopic().getDeletionComment()).isNull();
        }
    }

    @Test
    void doesNotExposeAnInvalidationTimeForANonTombstone() {
        StoredRecordMetadata inconsistentMetadata = new StoredRecordMetadata(
                RECORD_ID,
                List.of("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"),
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "source-001",
                NOW,
                NOW,
                NOW,
                null,
                null,
                StoredRecordMetadata.PublicationStatus.PUBLISHED,
                "ddb-v3",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                StoredRecordMetadata.ValidationStatus.VALID);

        DcatCatalogRecord result = mapper.toDcatCatalogRecord(inconsistentMetadata);

        assertThat(result.getPrimaryTopic().getInvalidatedAtTime()).isNull();
    }

    @Test
    void requiresAnAbsoluteDeletionReasonForATombstone() {
        assertThatThrownBy(() -> mapper.toDcatCatalogRecord(metadata(
                        StoredRecordMetadata.PublicationStatus.DELETED,
                        StoredRecordMetadata.ValidationStatus.VALID,
                        URI.create("provider-request"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A tombstone requires an absolute deletionReason IRI");

        assertThatThrownBy(() -> mapper.toDcatCatalogRecord(metadata(
                        StoredRecordMetadata.PublicationStatus.DELETED,
                        StoredRecordMetadata.ValidationStatus.VALID,
                        null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("A tombstone requires a deletionReason IRI");
    }

    @ParameterizedTest
    @CsvSource({
        "VALID, earl:passed, false",
        "VALID_WITH_WARNINGS, earl:passed, true",
        "INVALID, earl:failed, false",
        "NOT_VALIDATED, earl:untested, false"
    })
    void mapsValidationToEarl(
            StoredRecordMetadata.ValidationStatus status, String expectedOutcome, boolean expectsInfo) {
        DcatCatalogRecord result =
                mapper.toDcatCatalogRecord(metadata(StoredRecordMetadata.PublicationStatus.PUBLISHED, status));

        var testResult = result.getPrimaryTopic().getValidationAssertion().getResult();
        assertThat(testResult.getOutcome().getValue()).isEqualTo(expectedOutcome);
        assertThat(testResult.getInfo() != null).isEqualTo(expectsInfo);
    }

    private static StoredRecordMetadata metadata(
            StoredRecordMetadata.PublicationStatus publicationStatus,
            StoredRecordMetadata.ValidationStatus validationStatus) {
        return metadata(
                publicationStatus,
                validationStatus,
                publicationStatus == StoredRecordMetadata.PublicationStatus.DELETED
                        ? URI.create("https://example.org/deletion-reasons/provider-request")
                        : null);
    }

    private static StoredRecordMetadata metadata(
            StoredRecordMetadata.PublicationStatus publicationStatus,
            StoredRecordMetadata.ValidationStatus validationStatus,
            URI deletionReason) {
        return new StoredRecordMetadata(
                RECORD_ID,
                List.of("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"),
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "source-001",
                NOW,
                NOW,
                publicationStatus == StoredRecordMetadata.PublicationStatus.DELETED ? NOW : null,
                deletionReason,
                publicationStatus == StoredRecordMetadata.PublicationStatus.DELETED
                        ? "Vom Datenpartner zurueckgezogen."
                        : null,
                publicationStatus,
                "ddb-v3",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                validationStatus);
    }
}
