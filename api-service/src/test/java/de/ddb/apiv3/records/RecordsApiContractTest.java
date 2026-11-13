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
package de.ddb.apiv3.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestServer;
import de.ddb.apiv3.support.S3TestSupport;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises RDF negotiation, Jelly persistence and conditional writes at the HTTP boundary. */
@SpringBootTest(properties = "ddb.api.max-document-size=1KB")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RecordsApiContractTest extends S3TestSupport {

    private static final String RDF_XML = "application/rdf+xml";
    private static final byte[] RDF = S3TestServer.DEMO_RDF;
    private static final String TRIPLE = "<https://example.org/record/demo-001> "
            + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
            + "<http://www.europeana.eu/schemas/edm/ProvidedCHO> .";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RdfCodec codec;

    @Test
    void storesUncompressedJellyAtTheFixedRecordKeyWithoutAMetadataEnvelope() throws Exception {
        String id = "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV";
        int objectsBefore = STORAGE.recordCount();

        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(RDF))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", path(id)))
                .andExpect(header().exists("ETag"))
                .andExpect(content().string(""));

        assertStoredRdf(id, RDF);
        assertThat(STORAGE.storedRdf(id)).isNotEqualTo(RDF);
        assertThat(STORAGE.storedContentType(id + "/record")).isEqualTo(RdfCodec.JELLY_CONTENT_TYPE);
        assertThat(STORAGE.objectKeys()).contains(id + "/record");
        assertThat(STORAGE.objectKeys()).noneMatch(key -> key.endsWith(".json") || key.contains("/blobs/"));
        assertThat(STORAGE.recordCount()).isEqualTo(objectsBefore + 1);
    }

    @Test
    void acceptsRdfResponsePreferencesForBodylessWriteResponses() throws Exception {
        String id = "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKK";
        String etag = mockMvc.perform(post(path(id)).contentType(RDF_XML).accept(RDF_XML).content(RDF))
                .andExpect(status().isCreated())
                .andExpect(content().string(""))
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(put(path(id)).contentType(RDF_XML).accept(RDF_XML)
                        .header("If-Match", etag).content(replacementRdf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertStoredRdf(id, replacementRdf());
    }

    @Test
    void acceptsCommonJenaInputFormatsAndStoresTheSameCanonicalGraph() throws Exception {
        String[][] inputs = {
            {"A", "text/turtle", TRIPLE},
            {"B", "application/n-triples", TRIPLE},
            {"C", "application/trig", "<https://example.org/old-graph> { " + TRIPLE + " }"},
            {"D", "application/n-quads", TRIPLE.substring(0, TRIPLE.length() - 1)
                    + "<https://example.org/old-graph> ."},
            {"E", "application/ld+json", "{\"@id\":\"https://example.org/record/demo-001\","
                    + "\"@type\":\"http://www.europeana.eu/schemas/edm/ProvidedCHO\"}"}
        };
        for (String[] input : inputs) {
            String id = "BC".repeat(15) + "A" + input[0];
            mockMvc.perform(post(path(id)).contentType(input[1])
                            .content(input[2].getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isCreated());
            assertStoredRdf(id, RDF);
        }
    }

    @Test
    void acceptsJellyInputAndReplacesItsOriginalGraphName() throws Exception {
        String id = "BC".repeat(15) + "AF";
        byte[] uploadedJelly = codec.toJelly(RDF, RDF_XML, "https://example.org/previous-graph");

        mockMvc.perform(post(path(id)).contentType(RdfCodec.JELLY_CONTENT_TYPE).content(uploadedJelly))
                .andExpect(status().isCreated());

        assertStoredRdf(id, RDF);
    }

    @Test
    void negotiatesRdfOutputAndKeepsTheStorageValidatorSeparate() throws Exception {
        String id = "CD".repeat(16);
        String storageEtag = create(id);

        var defaultResponse = mockMvc.perform(get(path(id)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/trig"))
                .andExpect(header().string("ETag", "W/" + storageEtag))
                .andExpect(header().string("X-Record-ETag", storageEtag))
                .andReturn().getResponse();
        assertCanonicalDataset(id, defaultResponse.getContentAsByteArray(), Lang.TRIG, RDF, Lang.RDFXML);
        assertThat(String.join(",", defaultResponse.getHeaders("Vary"))).contains("Accept", "Accept-Encoding");

        for (String mediaType : List.of(RDF_XML, "text/turtle", "application/n-triples")) {
            var response = mockMvc.perform(get(path(id)).accept(mediaType))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(mediaType))
                    .andExpect(header().string("ETag", "W/" + storageEtag))
                    .andExpect(header().string("X-Record-ETag", storageEtag))
                    .andReturn().getResponse();
            assertGraph(response.getContentAsByteArray(),
                    RDFLanguages.contentTypeToLang(mediaType), RDF, Lang.RDFXML);
        }
        for (String mediaType : List.of("application/n-quads", "application/ld+json")) {
            var response = mockMvc.perform(get(path(id)).accept(mediaType))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(mediaType))
                    .andReturn().getResponse();
            assertCanonicalDataset(id, response.getContentAsByteArray(),
                    RDFLanguages.contentTypeToLang(mediaType), RDF, Lang.RDFXML);
        }
        var jelly = mockMvc.perform(get(path(id)).accept(RdfCodec.JELLY_CONTENT_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RdfCodec.JELLY_CONTENT_TYPE))
                .andReturn().getResponse().getContentAsByteArray();
        assertCanonicalDataset(id, codec.fromJelly(jelly, Lang.TRIG), Lang.TRIG, RDF, Lang.RDFXML);
    }

    @Test
    void mergesAllInputGraphsAndCanReturnTheirCompleteContentAsRdfXml() throws Exception {
        String id = "CE".repeat(16);
        String secondTriple = TRIPLE.replace("demo-001", "demo-002");
        String trig = "<https://example.org/first> { " + TRIPLE + " }\n"
                + "<https://example.org/second> { " + secondTriple + " }\n"
                + TRIPLE;
        byte[] expected = (TRIPLE + "\n" + secondTriple).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(path(id)).contentType("application/trig").content(trig))
                .andExpect(status().isCreated());

        assertCanonicalDataset(id, codec.fromJelly(STORAGE.storedRdf(id), Lang.TRIG),
                Lang.TRIG, expected, Lang.TURTLE);
        var response = mockMvc.perform(get(path(id)).accept(RDF_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RDF_XML))
                .andReturn().getResponse();
        assertGraph(response.getContentAsByteArray(), Lang.RDFXML, expected, Lang.TURTLE);
    }

    @Test
    void honorsAcceptQualityWildcardsAndExplicitExclusions() throws Exception {
        String id = "CF".repeat(16);
        create(id);

        mockMvc.perform(get(path(id)).header("Accept", "application/rdf+xml;q=0.2, text/turtle;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/turtle"));
        mockMvc.perform(get(path(id)).header("Accept", "application/rdf+xml;q=0, text/turtle;q=0.5"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/turtle"));
        mockMvc.perform(get(path(id)).accept("*/*"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/trig"));
        mockMvc.perform(get(path(id)).accept("application/x-unknown-rdf"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get(path(id)).header("Accept", "*/*;q=0"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void supportsConditionalReadsAndTheStrongReadValidatorForReplacement() throws Exception {
        String id = "CG".repeat(16);
        String storageEtag = create(id);

        for (String condition : List.of(storageEtag, "W/" + storageEtag, "\"stale\", W/" + storageEtag, "*")) {
            mockMvc.perform(get(path(id)).accept("text/turtle").header("If-None-Match", condition))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string("ETag", "W/" + storageEtag))
                    .andExpect(header().string("X-Record-ETag", storageEtag))
                    .andExpect(content().string(""));
        }
        mockMvc.perform(get(path(id)).header("If-None-Match", "\"stale\""))
                .andExpect(status().isOk());
        String validator = mockMvc.perform(get(path(id)))
                .andReturn().getResponse().getHeader("X-Record-ETag");
        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", validator)
                        .content(replacementRdf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void distinguishesAnUnknownRecordFromCorruptOrLegacyStorage() throws Exception {
        mockMvc.perform(get(path("CH".repeat(16))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        String legacyId = "CI".repeat(16);
        STORAGE.storeObject(legacyId + "/record", RDF, RDF_XML);
        mockMvc.perform(get(path(legacyId)).accept(RDF_XML))
                .andExpect(status().isServiceUnavailable());

        String corruptId = "CJ".repeat(16);
        STORAGE.storeObject(corruptId + "/record", new byte[] {0, 1, 2, 3}, RdfCodec.JELLY_CONTENT_TYPE);
        mockMvc.perform(get(path(corruptId)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void neverLoadsAnExternalDtdWhileCheckingRdf() throws Exception {
        AtomicInteger externalRequests = new AtomicInteger();
        HttpServer externalDtd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        externalDtd.createContext("/external.dtd", exchange -> {
            externalRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        externalDtd.start();
        try {
            String xml = new String(RDF, StandardCharsets.UTF_8).replace("?>", "?>\n"
                    + "<!DOCTYPE rdf:RDF SYSTEM \"http://127.0.0.1:" + externalDtd.getAddress().getPort()
                    + "/external.dtd\">");
            mockMvc.perform(post(path("OOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO"))
                            .contentType(RDF_XML).content(xml.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isCreated());
            assertThat(externalRequests).hasValue(0);
        } finally {
            externalDtd.stop(0);
        }
    }

    @Test
    void rejectsInvalidRdfBeforeAnyStorageWrite() throws Exception {
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(post(path("IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII"))
                        .contentType(RDF_XML).content("<rdf:RDF>"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_RDF"));

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void rejectsAnEmptyRdfDocumentBeforeAnyStorageWrite() throws Exception {
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(post(path("EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"))
                        .contentType(RDF_XML).content(new byte[0]))
                .andExpect(status().is4xxClientError());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void neverOverwritesAnExistingRecordDuringCreate() throws Exception {
        String id = "UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU";
        create(id);
        byte[] replacement = replacementRdf();

        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(replacement))
                .andExpect(status().isConflict());

        assertStoredRdf(id, RDF);
    }

    @Test
    void validatesRdfBeforeReplacingTheStoredObject() throws Exception {
        String id = "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR";
        String etag = create(id);
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", etag)
                        .content("not RDF"))
                .andExpect(status().isUnprocessableContent());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
        assertStoredRdf(id, RDF);

        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", etag)
                        .content(replacementRdf()))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Record-ETag"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(content().string(""));

        assertStoredRdf(id, replacementRdf());
    }

    @Test
    void rejectsAStaleWriteConditionWithoutChangingTheStoredRdf() throws Exception {
        String id = "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS";
        create(id);

        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", "\"stale\"")
                        .content(replacementRdf()))
                .andExpect(status().isPreconditionFailed());

        assertStoredRdf(id, RDF);
    }

    @Test
    void neverSalvagesATagOrWildcardFromAMalformedWriteCondition() throws Exception {
        String id = "PPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPP";
        String etag = create(id);
        int writesBefore = STORAGE.writeCount();

        for (String malformed : List.of("*, garbage", "garbage, *", "*, " + etag,
                etag + " garbage", "garbage " + etag, etag + ",", etag + ", \"unterminated", "W/" + etag)) {
            mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", malformed)
                            .content(replacementRdf()))
                    .andExpect(status().isPreconditionFailed());
        }
        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
        assertStoredRdf(id, RDF);
    }

    @Test
    void acceptsAValidEtagListWithoutTreatingWeakTagsAsMatches() throws Exception {
        String id = "TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT";
        String etag = create(id);

        mockMvc.perform(put(path(id)).contentType(RDF_XML)
                        .header("If-Match", "\"stale\", W/" + etag + ", " + etag)
                        .content(replacementRdf()))
                .andExpect(status().isNoContent());
        assertStoredRdf(id, replacementRdf());
    }

    @Test
    void requiresAnIfMatchConditionForReplacement() throws Exception {
        String id = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";
        create(id);
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(put(path(id)).contentType(RDF_XML).content(replacementRdf()))
                .andExpect(status().isPreconditionRequired());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void acceptsTheExistingObjectWildcardForReplacement() throws Exception {
        String id = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW";
        create(id);

        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", "*")
                        .content(replacementRdf()))
                .andExpect(status().isNoContent());

        assertStoredRdf(id, replacementRdf());
    }

    @Test
    void neverCreatesAnUnknownObjectThroughReplacement() throws Exception {
        mockMvc.perform(put(path("NNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN"))
                        .contentType(RDF_XML).header("If-Match", "*").content(RDF))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void rejectsUnsupportedMediaTypesBeforeStorage() throws Exception {
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(post(path("JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ"))
                        .contentType("application/x-unknown-rdf").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void returnsAProblemAndAllowHeaderForUnsupportedMethods() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, path("QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("GET"),
                        org.hamcrest.Matchers.containsString("POST"), org.hamcrest.Matchers.containsString("PUT"))))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void rejectsAnOversizedRawBodyBeforeStorage() throws Exception {
        int writesBefore = STORAGE.writeCount();
        byte[] oversized = new byte[1025];

        mockMvc.perform(post(path("LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL"))
                        .contentType(RDF_XML).content(oversized))
                .andExpect(status().isContentTooLarge());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "00000000000000000000000000000000"})
    void validatesTheDdbIdentifierBeforeStorage(String id) throws Exception {
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(RDF))
                .andExpect(status().isBadRequest());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @ParameterizedTest
    @CsvSource({
        "PATCH, /3/records/{recordId}, 405",
        "DELETE, /3/records/{recordId}, 405",
        "POST, /3/records, 404",
        "GET, /3/records/{recordId}/content, 404",
        "HEAD, /3/records/{recordId}/content, 404",
        "GET, /3/records/{recordId}/representations, 404",
        "GET, /3/records/{recordId}/source, 404",
        "GET, /3/records/{recordId}/display, 404",
        "POST, /3/records/{recordId}/item, 404",
        "POST, /3/records:batchGet, 404",
        "POST, /3/records:batchWrite, 404",
        "POST, /3/records:validate, 404",
        "GET, /contexts/record-metadata-v1.jsonld, 404",
        "GET, /contexts/ddb-api-v3-2.jsonld, 404"
    })
    void keepsRetiredOperationsUnavailable(String method, String path, int expectedStatus)
            throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method),
                        path.replace("{recordId}", S3TestServer.DEMO_ID)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void mapsStorageFailuresToServiceUnavailableAndKeepsLivenessIndependent() throws Exception {
        STORAGE.setAvailable(false);
        try {
            mockMvc.perform(post(path("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"))
                            .contentType(RDF_XML).content(RDF))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            mockMvc.perform(get(path("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            STORAGE.setAvailable(true);
        }
    }

    @Test
    void servesTheContractSwaggerAndHealth() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui.html"));
        mockMvc.perform(get("/openapi/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("operationId: createRecord")));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("url: \"/openapi/openapi.yaml\"")));
        mockMvc.perform(get("/webjars/swagger-ui/swagger-ui-bundle.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String create(String id) throws Exception {
        return mockMvc.perform(post(path(id)).contentType(RDF_XML).content(RDF))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("ETag");
    }

    private static String path(String id) {
        return "/3/records/" + id;
    }

    private static byte[] replacementRdf() {
        return new String(RDF, StandardCharsets.UTF_8).replace("demo-001", "demo-002")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void assertStoredRdf(String id, byte[] expectedRdfXml) {
        byte[] trig = codec.fromJelly(STORAGE.storedRdf(id), Lang.TRIG);
        assertCanonicalDataset(id, trig, Lang.TRIG, expectedRdfXml, Lang.RDFXML);
    }

    private static void assertCanonicalDataset(String id, byte[] bytes, Lang language,
            byte[] expectedRdf, Lang expectedLanguage) {
        Dataset dataset = DatasetFactory.createTxnMem();
        Model expected = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(dataset, new ByteArrayInputStream(bytes), language);
            RDFDataMgr.read(expected, new ByteArrayInputStream(expectedRdf), expectedLanguage);
            List<String> graphNames = new ArrayList<>();
            dataset.listNames().forEachRemaining(graphNames::add);
            assertThat(graphNames).containsExactly(graphName(id));
            assertThat(dataset.getDefaultModel().isEmpty()).isTrue();
            assertThat(dataset.getNamedModel(graphName(id)).isIsomorphicWith(expected)).isTrue();
        } finally {
            dataset.close();
            expected.close();
        }
    }

    private static void assertGraph(byte[] bytes, Lang language, byte[] expectedRdf, Lang expectedLanguage) {
        Model actual = ModelFactory.createDefaultModel();
        Model expected = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(actual, new ByteArrayInputStream(bytes), language);
            RDFDataMgr.read(expected, new ByteArrayInputStream(expectedRdf), expectedLanguage);
            assertThat(actual.isIsomorphicWith(expected)).isTrue();
        } finally {
            actual.close();
            expected.close();
        }
    }

    private static String graphName(String id) {
        return "https://api.deutsche-digitale-bibliothek.de/records/" + id;
    }
}
