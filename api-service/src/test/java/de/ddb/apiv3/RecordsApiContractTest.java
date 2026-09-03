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
package de.ddb.apiv3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.hamcrest.Matchers.matchesPattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP contract tests for the record API using the development persistence adapter.
 *
 * <p>MockMvc keeps these tests deterministic while still exercising generated controllers,
 * validation, conditional-request handling, RDF conversion and H2 persistence together. Embedded
 * server behavior such as transport compression is covered separately by
 * {@link HttpCompressionIntegrationTest}. Each write scenario uses a distinct stable record ID
 * because the Spring context and its in-memory database are shared across the class.</p>
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:contract-test;DB_CLOSE_DELAY=-1",
    "ddb.api.public-base-url=https://metadata.example/api",
    "ddb.resource-base-url=https://resources.example/catalog"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RecordsApiContractTest {

    private static final String DEMO_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String TOMBSTONE_ID = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
    private static final String UPLOAD_VALID_ID = "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV";
    private static final String UPLOAD_INVALID_ID = "IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII";
    private static final String UPLOAD_REPLACE_ID = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR";
    private static final String UPLOAD_DUPLICATE_ID = "UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU";
    private static final String UPLOAD_UNKNOWN_ID = "NNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN";
    private static final String UPLOAD_CONDITIONAL_ID = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";
    private static final String UPLOAD_INVALID_URI_ID = "LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL";
    private static final String UPLOAD_WILDCARD_TYPE_ID = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW";
    private static final MediaType JSON_LD = MediaType.parseMediaType("application/ld+json");
    private static final String RDF_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="https://example.org/record/demo-001">
                <rdf:type rdf:resource="http://www.europeana.eu/schemas/edm/ProvidedCHO"/>
              </rdf:Description>
            </rdf:RDF>
            """;
    private static final String CONTENT_ETAG =
            "\"rdfxml-stored-4210365e81dbc38157df7d95b6a819f311fbd205b5b65421e244b77d84a693fe\"";
    private static final String DEMO_METADATA_ETAG =
            "\"sha256-a2cb81e7b424bf0dc950cd01556223fb9605a94ff98c4774f091dd4fc2e96365\"";
    private static final String TOMBSTONE_METADATA_ETAG =
            "\"sha256-529ebbc11bd5cd0f1c7e9362b5edb8fe52f9eb1c8a717c6ed784327f595c739d\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    // Create and replace semantics, including RDF validation and optimistic concurrency.

    @Test
    void uploadsOnlyRdfXmlThatJenaCanParse() throws Exception {
        MockMultipartFile metadata = uploadMetadata(UPLOAD_VALID_ID, "upload-valid-001");
        MockMultipartFile content = new MockMultipartFile(
                "content", "record.rdf", "application/rdf+xml", RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var result = mockMvc.perform(multipart("/3/records")
                        .file(metadata)
                        .file(content)
                        .accept(JSON_LD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/3/records/" + UPLOAD_VALID_ID))
                .andExpect(header().string("ETag", matchesPattern("\"sha256-[a-f0-9]{64}\"")))
                .andExpect(jsonPath("$.primaryTopic.recordId").value(UPLOAD_VALID_ID))
                .andExpect(jsonPath("$.primaryTopic.sourceIdentifier.notation").value("upload-valid-001"))
                .andExpect(jsonPath("$.primaryTopic.validationAssertion.result.outcome").value("earl:untested"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        mockMvc.perform(get(location + "/content").accept("application/rdf+xml"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidRdfBeforeItCanBeStored() throws Exception {
        int recordsBefore = recordCount();
        MockMultipartFile invalidContent = new MockMultipartFile(
                "content",
                "invalid.rdf",
                "application/rdf+xml",
                "<rdf:RDF>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_INVALID_ID, "upload-invalid-001"))
                        .file(invalidContent)
                        .accept(JSON_LD))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_RDF"))
                .andExpect(jsonPath("$.detail").value("The content part is not valid RDF/XML"));

        assertThat(recordCount()).isEqualTo(recordsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/*"})
    void rejectsWildcardContentTypesForTheCanonicalRdfPart(String contentType) throws Exception {
        int recordsBefore = recordCount();
        MockMultipartFile content = new MockMultipartFile(
                "content",
                "record.rdf",
                contentType,
                RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_WILDCARD_TYPE_ID, "upload-wildcard-type"))
                        .file(content)
                        .accept(JSON_LD))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(recordCount()).isEqualTo(recordsBefore);
    }

    @Test
    void neverOverwritesAnExistingExternallyAssignedIdDuringCreate() throws Exception {
        byte[] originalRdf = RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_DUPLICATE_ID, "upload-original-001"))
                        .file(new MockMultipartFile(
                                "content", "original.rdf", "application/rdf+xml", originalRdf))
                        .accept(JSON_LD))
                .andExpect(status().isCreated());

        byte[] otherRdf = RDF_XML.replace("demo-001", "must-not-be-stored")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_DUPLICATE_ID, "upload-other-001"))
                        .file(new MockMultipartFile(
                                "content", "other.rdf", "application/rdf+xml", otherRdf))
                        .accept(JSON_LD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_EXISTS"));

        mockMvc.perform(get("/3/records/{recordId}/content", UPLOAD_DUPLICATE_ID)
                        .accept("application/rdf+xml"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(originalRdf));
    }

    @Test
    void putUpdatesOnlyAndNeverCreatesAnUnknownId() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/3/records/" + UPLOAD_UNKNOWN_ID)
                        .file(uploadMetadata(UPLOAD_UNKNOWN_ID, "upload-unknown-001"))
                        .file(new MockMultipartFile(
                                "content",
                                "unknown.rdf",
                                "application/rdf+xml",
                                RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("If-Match", "\"sha256-unknown\"")
                        .accept(JSON_LD))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesRdfBeforeReplacingAnExistingRecord() throws Exception {
        MockMultipartFile original = new MockMultipartFile(
                "content", "record.rdf", "application/rdf+xml", RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var created = mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_REPLACE_ID, "upload-replace-001"))
                        .file(original)
                        .accept(JSON_LD))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse();
        String location = created.getHeader("Location");
        String firstEtag = created.getHeader("ETag");

        MockMultipartFile invalid = new MockMultipartFile(
                "content", "invalid.rdf", "application/rdf+xml", "not RDF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart(HttpMethod.PUT, location)
                        .file(uploadMetadata(UPLOAD_REPLACE_ID, "upload-replace-001"))
                        .file(invalid)
                        .header("If-Match", firstEtag)
                        .accept(JSON_LD))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RDF"));

        byte[] replacementRdf = RDF_XML.replace("demo-001", "demo-002")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile replacement = new MockMultipartFile(
                "content", "replacement.rdf", "application/rdf+xml", replacementRdf);
        mockMvc.perform(multipart(HttpMethod.PUT, location)
                        .file(uploadMetadata(UPLOAD_REPLACE_ID, "upload-replace-001"))
                        .file(replacement)
                        .header("If-Match", firstEtag)
                        .accept(JSON_LD))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", matchesPattern("\"sha256-[a-f0-9]{64}\"")))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.not(firstEtag)));

        mockMvc.perform(get(location + "/content").accept("application/rdf+xml"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(replacementRdf));
    }

    @Test
    void acceptsIfMatchListsAndWildcardButRejectsWeakTags() throws Exception {
        byte[] originalRdf = RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var created = mockMvc.perform(multipart("/3/records")
                        .file(uploadMetadata(UPLOAD_CONDITIONAL_ID, "conditional-001"))
                        .file(new MockMultipartFile(
                                "content", "original.rdf", "application/rdf+xml", originalRdf))
                        .accept(JSON_LD))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse();

        byte[] secondRdf = RDF_XML.replace("demo-001", "conditional-002")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var replacedFromList = mockMvc.perform(multipart(
                        HttpMethod.PUT, "/3/records/" + UPLOAD_CONDITIONAL_ID)
                        .file(uploadMetadata(UPLOAD_CONDITIONAL_ID, "conditional-002"))
                        .file(new MockMultipartFile(
                                "content", "second.rdf", "application/rdf+xml", secondRdf))
                        .header("If-Match", "\"other\", " + created.getHeader("ETag"))
                        .accept(JSON_LD))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        byte[] thirdRdf = RDF_XML.replace("demo-001", "conditional-003")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var replacedFromWildcard = mockMvc.perform(multipart(
                        HttpMethod.PUT, "/3/records/" + UPLOAD_CONDITIONAL_ID)
                        .file(uploadMetadata(UPLOAD_CONDITIONAL_ID, "conditional-003"))
                        .file(new MockMultipartFile(
                                "content", "third.rdf", "application/rdf+xml", thirdRdf))
                        .header("If-Match", "*")
                        .accept(JSON_LD))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        mockMvc.perform(multipart(HttpMethod.PUT, "/3/records/" + UPLOAD_CONDITIONAL_ID)
                        .file(uploadMetadata(UPLOAD_CONDITIONAL_ID, "conditional-004"))
                        .file(new MockMultipartFile(
                                "content", "fourth.rdf", "application/rdf+xml", originalRdf))
                        .header("If-Match", "W/" + replacedFromWildcard.getHeader("ETag"))
                        .accept(JSON_LD))
                .andExpect(status().isPreconditionFailed());

        assertThat(replacedFromList.getHeader("ETag"))
                .isNotEqualTo(created.getHeader("ETag"));
    }

    @Test
    void rejectsMalformedSourceProfileUriAsBadRequest() throws Exception {
        String metadataJson = """
                {
                  "recordId": "%s",
                  "datasetIds": ["EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"],
                  "providerId": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                  "sourceId": "invalid-uri-001",
                  "publicationStatus": "PUBLISHED",
                  "profileVersion": "ddb-v3",
                  "sourceMediaType": "application/xml",
                  "sourceFormat": "LIDO",
                  "sourceFormatUri": "https://lido-schema.org/",
                  "sourceFormatVersion": "1.0",
                  "sourceProfile": "ddb",
                  "sourceProfileUri": "https://example.org/not valid"
                }
                """.formatted(UPLOAD_INVALID_URI_ID);

        mockMvc.perform(multipart("/3/records")
                        .file(new MockMultipartFile(
                                "metadata", "metadata.json", "application/json",
                                metadataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile(
                                "content", "record.rdf", "application/rdf+xml",
                                RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile(
                                "source", "source.xml", "application/xml", "<source/>".getBytes()))
                        .accept(JSON_LD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("sourceProfileUri is not a valid URI reference"));
    }

    // Documentation routing and the public DCAT administrative metadata contract.

    @Test
    void redirectsServiceRootToSwaggerUi() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui.html"))
                .andExpect(content().string(""));
    }

    @Test
    void getsRecordMetadataAccordingToContract() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", DEMO_ID)
                        .accept(JSON_LD))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(JSON_LD))
                .andExpect(header().string("ETag", DEMO_METADATA_ETAG))
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$['@context']")
                        .value("https://metadata.example/api/contexts/record-metadata-v1.jsonld"))
                .andExpect(jsonPath("$['@id']")
                        .value("https://metadata.example/api/3/records/" + DEMO_ID + "#metadata"))
                .andExpect(jsonPath("$['@type']").value("dcat:CatalogRecord"))
                .andExpect(jsonPath("$.primaryTopic['@id']")
                        .value("https://resources.example/catalog/item/" + DEMO_ID))
                .andExpect(jsonPath("$.primaryTopic['@type']").value("dcat:Dataset"))
                .andExpect(jsonPath("$.primaryTopic.recordId").value(DEMO_ID))
                .andExpect(jsonPath("$.primaryTopic.datasets[0]")
                        .value("https://resources.example/catalog/dataset/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))
                .andExpect(jsonPath("$.primaryTopic.datasets[1]")
                        .value("https://resources.example/catalog/dataset/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"))
                .andExpect(jsonPath("$.primaryTopic.provider")
                        .value("https://resources.example/catalog/organization/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"))
                .andExpect(jsonPath("$.primaryTopic.aggregator")
                        .value("https://resources.example/catalog/organization/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.primaryTopic.sourceIdentifier['@type']").value("adms:Identifier"))
                .andExpect(jsonPath("$.primaryTopic.sourceIdentifier.notation").value("demo-001"))
                .andExpect(jsonPath("$.primaryTopic.publicationStatus")
                        .value("http://publications.europa.eu/resource/authority/dataset-status/COMPLETED"))
                .andExpect(jsonPath("$.primaryTopic.validationAssertion.test")
                        .value("https://metadata.example/api/profiles/edm/ddb-v3"))
                .andExpect(jsonPath("$.primaryTopic.validationAssertion.result.outcome").value("earl:passed"));
    }

    @Test
    void returnsAdministrativeTombstoneButNeverItsRetainedContent() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", TOMBSTONE_ID)
                        .accept(JSON_LD))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", TOMBSTONE_METADATA_ETAG))
                .andExpect(jsonPath("$.primaryTopic.recordId").value(TOMBSTONE_ID))
                .andExpect(jsonPath("$.primaryTopic.publicationStatus")
                        .value("http://publications.europa.eu/resource/authority/dataset-status/WITHDRAWN"))
                .andExpect(jsonPath("$.primaryTopic.invalidatedAtTime")
                        .value("2026-01-02T12:00:00Z"))
                .andExpect(jsonPath("$.primaryTopic.deletionReason")
                        .value("https://example.org/deletion-reasons/provider-request"))
                .andExpect(jsonPath("$.primaryTopic.deletionComment")
                        .value("Vom Datenpartner zurueckgezogen."));

        mockMvc.perform(get("/3/records/{recordId}/content", TOMBSTONE_ID)
                        .accept("application/rdf+xml"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().doesNotExist("X-Content-SHA256"))
                .andExpect(jsonPath("$.code").value("RECORD_GONE"))
                .andExpect(jsonPath("$.detail")
                        .value("The record has a tombstone and no current content."));

        mockMvc.perform(head("/3/records/{recordId}/content", TOMBSTONE_ID)
                        .accept("application/rdf+xml"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("X-Content-SHA256"))
                .andExpect(content().string(""));
    }

    @Test
    void servesTheVersionedRecordMetadataContext() throws Exception {
        mockMvc.perform(get("/contexts/record-metadata-v1.jsonld"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(JSON_LD))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(jsonPath("$['@context'].dcat").value("http://www.w3.org/ns/dcat#"))
                .andExpect(jsonPath("$['@context'].recordId").value("dcterms:identifier"))
                .andExpect(jsonPath("$['@context'].provider['@id']").value("dcterms:publisher"))
                .andExpect(jsonPath("$['@context'].datasets['@id']").value("dcterms:isPartOf"))
                .andExpect(jsonPath("$['@context'].datasets['@container']").value("@set"))
                .andExpect(jsonPath("$['@context'].aggregator['@id']").value("edm:intermediateProvider"))
                .andExpect(jsonPath("$['@context'].adms").value("http://www.w3.org/ns/adms#"))
                .andExpect(jsonPath("$['@context'].edm").value("http://www.europeana.eu/schemas/edm/"))
                .andExpect(jsonPath("$['@context'].prov").value("http://www.w3.org/ns/prov#"))
                .andExpect(jsonPath("$['@context'].earl").value("http://www.w3.org/ns/earl#"))
                .andExpect(jsonPath("$['@context'].deletionReason['@id']").value("dcterms:type"))
                .andExpect(jsonPath("$['@context'].deletionComment").value("dcterms:description"))
                .andExpect(jsonPath("$['@context'].ddb").doesNotExist());
    }

    // HTTP validators and RDF content negotiation for immutable stored RDF/XML bytes.

    @Test
    void supportsConditionalGetWithEtag() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", DEMO_ID)
                        .header("If-None-Match", DEMO_METADATA_ETAG)
                        .accept(JSON_LD))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", DEMO_METADATA_ETAG))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*",
        "\"different\", W/\"sha256-a2cb81e7b424bf0dc950cd01556223fb9605a94ff98c4774f091dd4fc2e96365\""
    })
    void supportsWildcardWeakAndCommaSeparatedIfNoneMatch(String ifNoneMatch) throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", DEMO_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch)
                        .accept(JSON_LD))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", DEMO_METADATA_ETAG))
                .andExpect(content().string(""));
    }

    @Test
    void supportsConditionalGetWithLastModifiedDate() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", DEMO_ID)
                        .header("If-Modified-Since", "Thu, 1 Jan 2026 12:00:00 GMT")
                        .accept(JSON_LD))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    @Test
    void returnsCanonicalRdfXmlWithoutChangingStoredBytes() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .accept("application/rdf+xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rdf+xml"))
                .andExpect(content().bytes(RDF_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(header().string("Content-Length", "284"))
                .andExpect(header().string("ETag", CONTENT_ETAG))
                .andExpect(header().string("X-Content-SHA256",
                        "4210365e81dbc38157df7d95b6a819f311fbd205b5b65421e244b77d84a693fe"))
                .andExpect(header().string("Vary", "Accept, Accept-Encoding"));
    }

    @Test
    void convertsRdfXmlToTurtleOnlyWhenRequested() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .accept("text/turtle"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/turtle"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<https://example.org/record/demo-001>")))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.startsWith("\"turtle-jena-6.2.0-")));
    }

    @Test
    void treatsZeroQualityAcceptRangesAsSpecificExclusions() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .header(HttpHeaders.ACCEPT, "*/*;q=1, application/rdf+xml;q=0"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"));

        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .header(HttpHeaders.ACCEPT, "application/rdf+xml;q=0"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void usesTheConfiguredRecordIriAsNamedGraph() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .accept("application/n-quads"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/n-quads"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<https://metadata.example/api/3/records/" + DEMO_ID + ">")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "application/ld+json",
        "application/n-triples",
        "application/rdf+json",
        "application/trig",
        "application/trix",
        "application/rdf+thrift",
        "application/rdf+protobuf"
    })
    void supportsEveryConfiguredJenaOutputFormat(String mediaType) throws Exception {
        byte[] body = mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .accept(mediaType))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(mediaType))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(body).isNotEmpty();
    }

    @Test
    void headsCanonicalContentWithoutReturningABody() throws Exception {
        mockMvc.perform(head("/3/records/{recordId}/content", DEMO_ID)
                        .accept("application/rdf+xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/rdf+xml"))
                .andExpect(header().string("Content-Length", "284"))
                .andExpect(header().string("ETag", CONTENT_ETAG))
                .andExpect(content().string(""));
    }

    @Test
    void appliesContentRepresentationPreconditions() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}/content", DEMO_ID)
                        .header("If-None-Match", CONTENT_ETAG)
                        .accept("application/rdf+xml"))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", CONTENT_ETAG))
                .andExpect(content().string(""));
    }

    // Uniform problem responses plus the endpoints intentionally outside the record contract.

    @Test
    void returnsContractProblemForUnknownRecord() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", "22222222222222222222222222222222")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void validatesRecordIdAndReturnsContractProblem() throws Exception {
        mockMvc.perform(get("/3/records/{recordId}", "invalid")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].detail").isNotEmpty());
    }

    @Test
    void servesOriginalContractAndHealthEndpoint() throws Exception {
        mockMvc.perform(get("/openapi/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.1.0")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("operationId: getRecord")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("  - url: /")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("https://api.deutsche-digitale-bibliothek.de"))));

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "url: \"/openapi/openapi.yaml\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/webjars/swagger-ui/swagger-ui-bundle.js")));

        mockMvc.perform(get("/webjars/swagger-ui/swagger-ui-bundle.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private MockMultipartFile uploadMetadata(String recordId, String sourceId) {
        String json = """
                {
                  "recordId": "%s",
                  "datasetIds": [
                    "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE",
                    "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG"
                  ],
                  "providerId": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                  "aggregatorId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "sourceId": "%s",
                  "publicationStatus": "PUBLISHED",
                  "profileVersion": "ddb-v3"
                }
                """.formatted(recordId, sourceId);
        return new MockMultipartFile(
                "metadata",
                "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int recordCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM record_metadata_by_id")
                .query(Integer.class)
                .single();
    }
}
