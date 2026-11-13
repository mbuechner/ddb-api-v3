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
import de.ddb.apiv3.generated.model.AdministrativeRecord;
import de.ddb.apiv3.generated.model.DdbObjectMetadata;
import de.ddb.apiv3.generated.model.EarlTestResult;
import de.ddb.apiv3.generated.model.EarlValidationAssertion;
import de.ddb.apiv3.generated.model.RecordInvalidation;
import de.ddb.apiv3.record.StoredRecordMetadata;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Creates the public administrative JSON-LD document from host-neutral stored metadata. */
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
        this.contextIri = iri(this.apiBaseUrl, "contexts", RecordMetadataContextDocument.CURRENT_FILE_NAME).toString();
    }

    /**
     * Projects administrative facts into BIBFRAME/FOAF and object facts into the existing EDM aggregation.
     * The DDB URLs identify resources and validation criteria; none of them defines vocabulary terms.
     *
     * @param metadata deployment-neutral stored metadata
     * @return administrative record with explicit links in both directions
     */
    public AdministrativeRecord toAdministrativeRecord(StoredRecordMetadata metadata) {
        URI recordEndpointIri = iri(apiBaseUrl, "3", "records", metadata.recordId());
        URI apiRecordIri = URI.create(fragment(recordEndpointIri, "metadata"));
        URI itemIri = iri(resourceBaseUrl, "item", metadata.recordId());
        Set<URI> datasetIris = datasetIris(metadata, false);
        Set<URI> recordDatasetIris = datasetIris(metadata, true);
        String providerIri = iri(resourceBaseUrl, "organization", metadata.providerId()).toString();
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
                .atId(fragment(recordEndpointIri, "validation"))
                .atType(EarlValidationAssertion.AtTypeEnum.EARL_ASSERTION)
                .subject(recordEndpointIri)
                .test(profileIri)
                .mode(EarlValidationAssertion.ModeEnum.EARL_AUTOMATIC)
                .result(testResult);

        DdbObjectMetadata resource = new DdbObjectMetadata()
                .atId(itemIri)
                .atType(DdbObjectMetadata.AtTypeEnum.ORE_AGGREGATION)
                .recordId(metadata.recordId())
                .datasets(datasetIris)
                .isPrimaryTopicOf(Set.of(apiRecordIri))
                .adminMetadata(Set.of(apiRecordIri))
                .provider(URI.create(providerIri))
                .aggregator(aggregatorIri == null ? null : URI.create(aggregatorIri))
                .sourceIdentifier(sourceIdentifier(metadata.sourceId(), providerIri))
                .invalidatedAtTime(metadata.isTombstone() ? metadata.deletedAt() : null)
                .wasInvalidatedBy(metadata.isTombstone() ? new RecordInvalidation()
                        .atId(URI.create(fragment(recordEndpointIri, "deletion")))
                        .atType(RecordInvalidation.AtTypeEnum.PROV_ACTIVITY)
                        .deletionReason(deletionReason(metadata))
                        .deletionComment(metadata.deletionComment()) : null);

        return new AdministrativeRecord()
                .atContext(URI.create(contextIri))
                .atId(apiRecordIri)
                .atType(Set.of(AdministrativeRecord.AtTypeEnum.BF_ADMIN_METADATA,
                        AdministrativeRecord.AtTypeEnum.FOAF_DOCUMENT))
                .primaryTopic(resource)
                .adminMetadataFor(itemIri)
                .datasets(recordDatasetIris)
                .recordProfile(iri(apiBaseUrl, "profiles", "record-metadata", "ddb-v2"))
                .publicationStatus(publicationStatus(metadata.publicationStatus()))
                .validationAssertion(validationAssertion)
                .createdAt(metadata.createdAt())
                .modifiedAt(metadata.modifiedAt());
    }

    private Set<URI> datasetIris(StoredRecordMetadata metadata, boolean records) {
        Set<URI> result = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        ids.add("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ids.addAll(metadata.datasetIds());
        ids.stream().map(id -> records ? iri(resourceBaseUrl, "dataset", id, "records")
                : iri(resourceBaseUrl, "dataset", id)).forEach(result::add);
        return result;
    }

    private static AdmsIdentifier sourceIdentifier(String sourceId, String providerIri) {
        return sourceId == null
                ? null
                : new AdmsIdentifier().atType(AdmsIdentifier.AtTypeEnum.ADMS_IDENTIFIER).notation(sourceId)
                        .creator(URI.create(providerIri));
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

    private static AdministrativeRecord.PublicationStatusEnum publicationStatus(
            StoredRecordMetadata.PublicationStatus status) {
        return switch (status) {
            case DRAFT -> AdministrativeRecord.PublicationStatusEnum
                    .HTTP_PUBLICATIONS_EUROPA_EU_RESOURCE_AUTHORITY_DATASET_STATUS_DEVELOP;
            case PUBLISHED -> AdministrativeRecord.PublicationStatusEnum
                    .HTTP_PUBLICATIONS_EUROPA_EU_RESOURCE_AUTHORITY_DATASET_STATUS_COMPLETED;
            case WITHDRAWN, DELETED -> AdministrativeRecord.PublicationStatusEnum
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
