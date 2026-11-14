/* 
 *  Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 *  This code was made with help from OpenAI GTP-6.1 Sol Ultra.
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Exercises RDF negotiation, Jelly persistence and conditional writes at the HTTP boundary. */
@SpringBootTest(properties = {"ddb.api.max-document-size=1KB",
        "ddb.api.base-url=https://api.deutsche-digitale-bibliothek.de"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@WithMockUser(username = "test-writer", authorities = "SCOPE_records:write")
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
    void rejectsEveryRetiredInputFormatForCreateAndReplacementBeforeStorage() throws Exception {
        String existingId = "BC".repeat(15) + "AF";
        String newId = "BC".repeat(15) + "AG";
        String etag = create(existingId);
        var inputs = Map.of(
                "text/turtle", TRIPLE.getBytes(StandardCharsets.UTF_8),
                "application/n-triples", TRIPLE.getBytes(StandardCharsets.UTF_8),
                "application/trig", ("<https://example.org/graph> { " + TRIPLE + " }")
                        .getBytes(StandardCharsets.UTF_8),
                "application/n-quads", (TRIPLE.substring(0, TRIPLE.length() - 1)
                        + "<https://example.org/graph> .").getBytes(StandardCharsets.UTF_8),
                "application/ld+json", "{}".getBytes(StandardCharsets.UTF_8),
                RdfCodec.JELLY_CONTENT_TYPE, codec.toJelly(RDF, "https://example.org/graph"));
        int writesBefore = STORAGE.writeCount();

        for (var input : inputs.entrySet()) {
            mockMvc.perform(post(path(newId)).contentType(input.getKey()).content(input.getValue()))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            mockMvc.perform(put(path(existingId)).contentType(input.getKey()).header("If-Match", etag)
                            .content(input.getValue()))
                    .andExpect(status().isUnsupportedMediaType());
        }

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
        assertStoredRdf(existingId, RDF);
    }

    @Test
    void negotiatesRdfOutputAndKeepsTheStorageValidatorSeparate() throws Exception {
        String id = "CD".repeat(16);
        String storageEtag = create(id);

        var defaultResponse = mockMvc.perform(get(path(id)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"))
                .andExpect(header().string("ETag", "W/" + storageEtag))
                .andExpect(header().string("X-Record-ETag", storageEtag))
                .andReturn().getResponse();
        assertCanonicalDataset(id, defaultResponse.getContentAsByteArray(), Lang.JSONLD, RDF, Lang.RDFXML);
        assertThat(String.join(",", defaultResponse.getHeaders("Vary"))).contains("Accept", "Accept-Encoding");

        var xml = mockMvc.perform(get(path(id)).accept(RDF_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RDF_XML))
                .andExpect(header().string("ETag", "W/" + storageEtag))
                .andExpect(header().string("X-Record-ETag", storageEtag))
                .andReturn().getResponse().getContentAsByteArray();
        assertGraph(xml, Lang.RDFXML, RDF, Lang.RDFXML);
        var jelly = mockMvc.perform(get(path(id)).accept(RdfCodec.JELLY_CONTENT_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RdfCodec.JELLY_CONTENT_TYPE))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(jelly).containsExactly(STORAGE.storedRdf(id));
        assertCanonicalDataset(id, codec.fromJelly(jelly, Lang.JSONLD), Lang.JSONLD, RDF, Lang.RDFXML);
    }

    @Test
    void returnsFlatTypedXmlAndCompactNamedJsonLd() throws Exception {
        String id = "DK".repeat(16);
        byte[] rdf = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="https://example.org/">
                  <ex:Record rdf:about="https://example.org/first">
                    <ex:related rdf:resource="https://example.org/second"/>
                    <ex:detail rdf:nodeID="detail"/>
                  </ex:Record>
                  <ex:Item rdf:about="https://example.org/second"><ex:label>first&#10;second</ex:label></ex:Item>
                  <ex:Detail rdf:nodeID="detail"><ex:label>detail</ex:label></ex:Detail>
                </rdf:RDF>
                """.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(rdf))
                .andExpect(status().isCreated());

        byte[] xml = mockMvc.perform(get(path(id)).accept(RDF_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RDF_XML))
                .andReturn().getResponse().getContentAsByteArray();
        var xmlFactory = DocumentBuilderFactory.newInstance();
        xmlFactory.setNamespaceAware(true);
        Document document = xmlFactory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Element record = assertTopLevelTypedNode(document, "Record");
        assertTopLevelTypedNode(document, "Item");
        assertTopLevelTypedNode(document, "Detail");
        Element related = (Element) record.getElementsByTagNameNS("https://example.org/", "related").item(0);
        assertThat(related.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "resource"))
                .isEqualTo("https://example.org/second");
        assertThat(related.getElementsByTagName("*").getLength()).isZero();
        Element detail = (Element) record.getElementsByTagNameNS("https://example.org/", "detail").item(0);
        assertThat(detail.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "nodeID"))
                .isNotEmpty();
        assertThat(detail.getElementsByTagName("*").getLength()).isZero();
        assertGraph(xml, Lang.RDFXML, rdf, Lang.RDFXML);

        byte[] jsonLd = mockMvc.perform(get(path(id)).accept("application/ld+json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"))
                .andExpect(jsonPath("$['@context']").isMap())
                .andExpect(jsonPath("$['@context'].ex").value("https://example.org/"))
                .andExpect(jsonPath("$['@graph']").isArray())
                .andExpect(jsonPath("$['@id']").value(graphName(id)))
                .andExpect(jsonPath("$['@graph'].length()").value(3))
                .andReturn().getResponse().getContentAsByteArray();
        assertCanonicalDataset(id, jsonLd, Lang.JSONLD, rdf, Lang.RDFXML);
    }

    @Test
    void rejectsRetiredSerializationsAsOutput() throws Exception {
        String id = "DL".repeat(16);
        create(id);

        for (String mediaType : List.of("text/turtle", "application/trig", "application/n-quads",
                "application/n-triples", "application/rdf+json",
                "application/trix", "application/trix+xml", "text/n3", "application/rdf+protobuf")) {
            mockMvc.perform(get(path(id)).accept(mediaType))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test
    void honorsAcceptQualityWildcardsAndExplicitExclusions() throws Exception {
        String id = "CF".repeat(16);
        create(id);

        mockMvc.perform(get(path(id)).header("Accept", "application/rdf+xml;q=0.2, application/ld+json;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"));
        mockMvc.perform(get(path(id)).header("Accept", "application/ld+json;q=0, application/rdf+xml;q=0.5"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RDF_XML));
        mockMvc.perform(get(path(id)).accept("*/*"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"));
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
            mockMvc.perform(get(path(id)).accept(RDF_XML).header("If-None-Match", condition))
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
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(header().exists("X-Request-Id"));

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
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Unprocessable Content"))
                .andExpect(jsonPath("$.detail").value("The request body is not valid RDF/XML"))
                .andExpect(jsonPath("$.instance").value(path("IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII")));

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
    void rejectsEmptyGraphsForCreateAndReplacementWithoutTouchingStorage() throws Exception {
        String existingId = "ER".repeat(16);
        String newId = "ES".repeat(16);
        String etag = create(existingId);
        byte[] original = STORAGE.storedRdf(existingId);
        int writesBefore = STORAGE.writeCount();
        int objectsBefore = STORAGE.recordCount();

        for (String emptyGraph : List.of(
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>",
                """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="https://example.org/empty"/>
                </rdf:RDF>
                """)) {
            mockMvc.perform(post(path(newId)).contentType(RDF_XML).content(emptyGraph))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            mockMvc.perform(put(path(existingId)).contentType(RDF_XML).header("If-Match", etag)
                            .content(emptyGraph))
                    .andExpect(status().isUnprocessableContent());
        }

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
        assertThat(STORAGE.recordCount()).isEqualTo(objectsBefore);
        assertThat(STORAGE.objectKeys()).doesNotContain(newId + "/record");
        assertThat(STORAGE.storedRdf(existingId)).containsExactly(original);
        mockMvc.perform(get(path(existingId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Record-ETag", etag));
    }

    @Test
    void neverOverwritesAnExistingRecordDuringCreate() throws Exception {
        String id = "UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU";
        create(id);
        byte[] replacement = replacementRdf();

        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(replacement))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));

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
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"));

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void securityRejectsUnsupportedUnsafeMethods() throws Exception {
        mockMvc.perform(request(HttpMethod.PATCH, path("QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ")))
                .andExpect(status().isForbidden());
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
    void validatesTheDdbIdentifierForWritesBeforeStorage(String id) throws Exception {
        int writesBefore = STORAGE.writeCount();

        mockMvc.perform(post(path(id)).contentType(RDF_XML).content(RDF))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(path(id)).contentType(RDF_XML).header("If-Match", "*").content(RDF))
                .andExpect(status().isBadRequest());

        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "00000000000000000000000000000000", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void anInvalidReadIdentifierIsAnUnknownResource(String id) throws Exception {
        mockMvc.perform(get(path(id)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void malformedAcceptRemainsABadRequestForAnExistingRecord() throws Exception {
        String id = "DQ".repeat(16);
        create(id);

        mockMvc.perform(get(path(id)).header("Accept", "not a media type"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({
        "PATCH, /3/records/{recordId}, 403",
        "DELETE, /3/records/{recordId}, 403",
        "POST, /3/records, 403",
        "GET, /3/records/{recordId}/content, 404",
        "HEAD, /3/records/{recordId}/content, 404",
        "GET, /3/records/{recordId}/representations, 404",
        "GET, /3/records/{recordId}/source, 404",
        "GET, /3/records/{recordId}/display, 404",
        "POST, /3/records/{recordId}/item, 403",
        "POST, /3/records:batchGet, 403",
        "POST, /3/records:batchWrite, 403",
        "POST, /3/records:validate, 403",
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
                    .andExpect(jsonPath("$.detail").value("The persistence backend is temporarily unavailable"));
            mockMvc.perform(get(path("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("The persistence backend is temporarily unavailable"));
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
        byte[] jsonLd = codec.fromJelly(STORAGE.storedRdf(id), Lang.JSONLD);
        assertCanonicalDataset(id, jsonLd, Lang.JSONLD, expectedRdfXml, Lang.RDFXML);
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

    private static Element assertTopLevelTypedNode(Document document, String type) {
        var nodes = document.getElementsByTagNameNS("https://example.org/", type);
        assertThat(nodes.getLength()).isEqualTo(1);
        Element element = (Element) nodes.item(0);
        assertThat(element.getParentNode()).isSameAs(document.getDocumentElement());
        return element;
    }

    private static String graphName(String id) {
        return "https://api.deutsche-digitale-bibliothek.de/records/" + id;
    }
}
