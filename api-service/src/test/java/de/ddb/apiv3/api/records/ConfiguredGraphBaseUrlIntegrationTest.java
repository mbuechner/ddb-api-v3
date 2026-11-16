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

package de.ddb.apiv3.api.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

/** A configured origin names the RDF graph; request hosts and S3 object keys stay separate. */
@SpringBootTest(properties = "ddb.api.base-url=https://rdf.example.org:8443/")
@AutoConfigureMockMvc
@WithMockUser(username = "test-writer", authorities = "SCOPE_records:write")
@ActiveProfiles("dev")
class ConfiguredGraphBaseUrlIntegrationTest extends S3TestSupport {

    private static final String ID = "DM".repeat(16);
    private static final String PATH = "/3/records/" + ID;
    private static final String ORIGIN = "https://rdf.example.org:8443";
    private static final String GRAPH = ORIGIN + "/records/" + ID;
    private static final Node PREDICATE = NodeFactory.createURI("https://example.org/p");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RdfCodec codec;

    @Test
    void createsAndReplacesTheConfiguredGraphWithoutUsingTheRequestHost() throws Exception {
        int objectsBefore = STORAGE.recordCount();
        String storageEtag = mockMvc.perform(post(PATH).contentType("application/rdf+xml")
                        .header("Host", "request.example.org:9999")
                        .header("X-Forwarded-Host", "forwarded.example.org")
                        .content(rdf("created")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PATH))
                .andReturn().getResponse().getHeader("ETag");

        assertThat(STORAGE.objectKeys()).contains(ID + "/record");
        assertThat(STORAGE.recordCount()).isEqualTo(objectsBefore + 1);
        assertGraph(codec.fromJelly(STORAGE.storedRdf(ID), Lang.JSONLD), "created");

        mockMvc.perform(put(PATH).contentType("application/rdf+xml").header("If-Match", storageEtag)
                        .header("Host", "another-request.example.org")
                        .content(rdf("updated")))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Record-ETag"));

        assertThat(STORAGE.recordCount()).isEqualTo(objectsBefore + 1);
        assertGraph(codec.fromJelly(STORAGE.storedRdf(ID), Lang.JSONLD), "updated");
        byte[] retrieved = mockMvc.perform(get(PATH).accept("application/ld+json")
                        .header("Host", "yet-another-request.example.org"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"))
                .andReturn().getResponse().getContentAsByteArray();
        assertGraph(retrieved, "updated");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resolvesRelativeDeliveryIrisWithoutAddingTheApiVersion(boolean rootBase) throws Exception {
        String id = (rootBase ? "DR" : "DS").repeat(16);
        String referenceRoot = rootBase ? "" : "/";
        byte[] source = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="https://example.org/" %s>
                  <rdf:Description rdf:about="%srecords/%s">
                    <ex:p rdf:resource="%sitems/%s#part"/>
                    <ex:source rdf:resource="https://external.example.org/item/42"/>
                  </rdf:Description>
                </rdf:RDF>
                """.formatted(rootBase ? "xml:base=\"/\"" : "", referenceRoot, id, referenceRoot, id)
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/3/records/" + id).contentType("application/rdf+xml").content(source))
                .andExpect(status().isCreated());

        var dataset = RDFParser.source(new ByteArrayInputStream(codec.fromJelly(STORAGE.storedRdf(id), Lang.JSONLD)))
                .lang(Lang.JSONLD).toDatasetGraph();
        Node record = NodeFactory.createURI(ORIGIN + "/records/" + id);
        try {
            assertThat(dataset.listGraphNodes()).toIterable().containsExactly(record);
            var graph = dataset.getGraph(record);
            assertThat(graph.size()).isEqualTo(2);
            assertThat(graph.contains(record, PREDICATE,
                    NodeFactory.createURI(ORIGIN + "/items/" + id + "#part"))).isTrue();
            assertThat(graph.contains(record, NodeFactory.createURI("https://example.org/source"),
                    NodeFactory.createURI("https://external.example.org/item/42"))).isTrue();
        } finally {
            dataset.close();
        }
    }

    @Test
    void anAbsoluteDeliveryIriIsNotRewrittenByTheXmlBase() throws Exception {
        String id = "DT".repeat(16);
        String original = "https://producer.example.org/items/" + id;
        byte[] source = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="https://example.org/" xml:base="/">
                  <rdf:Description rdf:about="%s"><ex:p rdf:resource="%s"/></rdf:Description>
                </rdf:RDF>
                """.formatted(original, original).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/3/records/" + id).contentType("application/rdf+xml").content(source))
                .andExpect(status().isCreated());
        var dataset = RDFParser.source(new ByteArrayInputStream(codec.fromJelly(STORAGE.storedRdf(id), Lang.JSONLD)))
                .lang(Lang.JSONLD).toDatasetGraph();
        try {
            Node node = NodeFactory.createURI(original);
            assertThat(dataset.getGraph(NodeFactory.createURI(ORIGIN + "/records/" + id))
                    .contains(node, PREDICATE, node)).isTrue();
        } finally {
            dataset.close();
        }
    }

    private static byte[] rdf(String fragment) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="https://example.org/">
                  <rdf:Description rdf:about=""><ex:p rdf:resource="#%s"/></rdf:Description>
                </rdf:RDF>
                """.formatted(fragment).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertGraph(byte[] jsonLd, String fragment) {
        var dataset = RDFParser.create().source(new ByteArrayInputStream(jsonLd)).lang(Lang.JSONLD).toDatasetGraph();
        Node graphName = NodeFactory.createURI(GRAPH);
        try {
            List<Node> graphNames = new ArrayList<>();
            dataset.listGraphNodes().forEachRemaining(graphNames::add);
            assertThat(graphNames).containsExactly(graphName);
            assertThat(dataset.getDefaultGraph().isEmpty()).isTrue();
            var graph = dataset.getGraph(graphName);
            assertThat(graph.size()).isEqualTo(1);
            assertThat(graph.contains(graphName, PREDICATE, NodeFactory.createURI(GRAPH + "#" + fragment))).isTrue();
        } finally {
            dataset.close();
        }
    }
}
