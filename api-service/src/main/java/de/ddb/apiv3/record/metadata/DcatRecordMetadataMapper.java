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

import de.ddb.apiv3.generated.model.AdmsIdentifier;
import de.ddb.apiv3.generated.model.DcatCatalogRecord;
import de.ddb.apiv3.generated.model.DcatDataset;
import de.ddb.apiv3.generated.model.EarlTestResult;
import de.ddb.apiv3.generated.model.EarlValidationAssertion;
import de.ddb.apiv3.record.StoredRecordMetadata;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Creates the public DCAT 3 JSON-LD representation from host-neutral stored metadata. */
@Component
public final class DcatRecordMetadataMapper {

    private final URI apiBaseUrl;
    private final URI resourceBaseUrl;
    private final String contextIri;

    public DcatRecordMetadataMapper(
            @Value("${ddb.api.public-base-url}") String apiBaseUrl,
            @Value("${ddb.resource-base-url}") String resourceBaseUrl) {
        this.apiBaseUrl = normalizeBaseUrl(apiBaseUrl, "ddb.api.public-base-url");
        this.resourceBaseUrl = normalizeBaseUrl(resourceBaseUrl, "ddb.resource-base-url");
        this.contextIri = iri(this.apiBaseUrl, "contexts", RecordMetadataContextDocument.FILE_NAME).toString();
    }

    /**
     * Projects stored facts into compact JSON-LD using DCAT and its established supporting vocabularies.
     * Europeana EDM remains available for facts that cannot be represented adequately by DCAT.
     * The DDB URLs identify resources and validation criteria; none of them defines vocabulary terms.
     *
     * @param metadata deployment-neutral stored metadata
     * @return DCAT catalog record with absolute IRIs for the configured API and resource origins
     */
    public DcatCatalogRecord toDcatCatalogRecord(StoredRecordMetadata metadata) {
        URI apiRecordIri = iri(apiBaseUrl, "3", "records", metadata.recordId());
        URI itemIri = iri(resourceBaseUrl, "item", metadata.recordId());
        Set<String> datasetIris = resourceIris("dataset", metadata.datasetIds());
        String aggregatorIri = metadata.aggregatorId() == null
                ? null
                : iri(resourceBaseUrl, "organization", metadata.aggregatorId()).toString();
        String profileIri = iri(apiBaseUrl, "profiles", "edm", metadata.profileVersion()).toString();

        EarlTestResult testResult = new EarlTestResult()
                .atType(EarlTestResult.AtTypeEnum.EARL_TEST_RESULT)
                .outcome(validationOutcome(metadata.validationStatus()))
                .info(metadata.validationStatus() == StoredRecordMetadata.ValidationStatus.VALID_WITH_WARNINGS
                        ? "Validation passed with warnings."
                        : null);
        EarlValidationAssertion validationAssertion = new EarlValidationAssertion()
                .atId(fragment(apiRecordIri, "validation"))
                .atType(EarlValidationAssertion.AtTypeEnum.EARL_ASSERTION)
                .test(profileIri)
                .mode(EarlValidationAssertion.ModeEnum.EARL_AUTOMATIC)
                .result(testResult);

        DcatDataset resource = new DcatDataset()
                .atId(itemIri.toString())
                .atType(DcatDataset.AtTypeEnum.DCAT_DATASET)
                .recordId(metadata.recordId())
                .datasets(datasetIris)
                .provider(iri(resourceBaseUrl, "organization", metadata.providerId()).toString())
                .aggregator(aggregatorIri)
                .sourceIdentifier(sourceIdentifier(metadata.sourceId()))
                .publicationStatus(publicationStatus(metadata.publicationStatus()))
                .invalidatedAtTime(metadata.isTombstone() ? metadata.deletedAt() : null)
                .deletionReason(deletionReason(metadata))
                .deletionComment(metadata.isTombstone() ? metadata.deletionComment() : null)
                .validationAssertion(validationAssertion);

        return new DcatCatalogRecord()
                .atContext(contextIri)
                .atId(fragment(apiRecordIri, "metadata"))
                .atType(DcatCatalogRecord.AtTypeEnum.DCAT_CATALOG_RECORD)
                .primaryTopic(resource)
                .createdAt(metadata.createdAt())
                .modifiedAt(metadata.modifiedAt());
    }

    private Set<String> resourceIris(String resourceType, java.util.List<String> ids) {
        Set<String> result = new LinkedHashSet<>();
        ids.stream()
                .map(id -> iri(resourceBaseUrl, resourceType, id).toString())
                .forEach(result::add);
        return result;
    }

    private static AdmsIdentifier sourceIdentifier(String sourceId) {
        return sourceId == null
                ? null
                : new AdmsIdentifier().atType(AdmsIdentifier.AtTypeEnum.ADMS_IDENTIFIER).notation(sourceId);
    }

    private static URI deletionReason(StoredRecordMetadata metadata) {
        if (!metadata.isTombstone()) {
            return null;
        }
        URI reason = Objects.requireNonNull(
                metadata.deletionReason(), "A tombstone requires a deletionReason IRI");
        if (!reason.isAbsolute()) {
            throw new IllegalArgumentException("A tombstone requires an absolute deletionReason IRI");
        }
        return reason;
    }

    private static DcatDataset.PublicationStatusEnum publicationStatus(
            StoredRecordMetadata.PublicationStatus status) {
        return switch (status) {
            case DRAFT -> DcatDataset.PublicationStatusEnum
                    .HTTP_PUBLICATIONS_EUROPA_EU_RESOURCE_AUTHORITY_DATASET_STATUS_DEVELOP;
            case PUBLISHED -> DcatDataset.PublicationStatusEnum
                    .HTTP_PUBLICATIONS_EUROPA_EU_RESOURCE_AUTHORITY_DATASET_STATUS_COMPLETED;
            case WITHDRAWN, DELETED -> DcatDataset.PublicationStatusEnum
                    .HTTP_PUBLICATIONS_EUROPA_EU_RESOURCE_AUTHORITY_DATASET_STATUS_WITHDRAWN;
        };
    }

    private static EarlTestResult.OutcomeEnum validationOutcome(StoredRecordMetadata.ValidationStatus status) {
        return switch (status) {
            case VALID, VALID_WITH_WARNINGS -> EarlTestResult.OutcomeEnum.EARL_PASSED;
            case INVALID -> EarlTestResult.OutcomeEnum.EARL_FAILED;
            case NOT_VALIDATED -> EarlTestResult.OutcomeEnum.EARL_UNTESTED;
        };
    }

    private static URI iri(URI baseUrl, String... pathSegments) {
        return UriComponentsBuilder.fromUri(baseUrl)
                .pathSegment(pathSegments)
                .build()
                .encode()
                .toUri();
    }

    private static String fragment(URI iri, String fragment) {
        return UriComponentsBuilder.fromUri(iri).fragment(fragment).build().encode().toUriString();
    }

    private static URI normalizeBaseUrl(String value, String propertyName) {
        URI uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        String scheme = uri.getScheme();
        boolean isHttp = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!isHttp
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    propertyName + " must be an absolute HTTP(S) URL without user info, query or fragment");
        }
        return uri;
    }
}
