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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestSupport;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

/** Resolve delivery IRIs from the actual public request origin, without a version in RDF. */
@SpringBootTest(properties = "ddb.api.base-url=auto")
@AutoConfigureMockMvc
@WithMockUser(username = "test-writer", authorities = "SCOPE_records:write")
@ActiveProfiles("dev")
class RequestGraphBaseUrlIntegrationTest extends S3TestSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RdfCodec codec;

    @ParameterizedTest
    @CsvSource({"DU, http://localhost:8080", "DV, http://localhost:9090", "DW, https://preview.example.org:8443"})
    void resolvesRootBasedXmlAgainstTheRequestSchemeHostAndPort(String prefix, String origin) throws Exception {
        String id = prefix.repeat(16);
        mvc.perform(post(URI.create(origin + "/3/records/" + id)).contentType("application/rdf+xml")
                        // Forwarded headers require an explicitly trusted proxy strategy.
                        .header("Forwarded", "proto=https;host=untrusted.example")
                        .header("X-Forwarded-Host", "untrusted.example")
                        .content(rdf(id)))
                .andExpect(status().isCreated());
        assertStoredGraph(id, origin);
    }

    @Test
    void resolvesAnUpdateAgainButDoesNotRewriteStoredIrisOnRead() throws Exception {
        String id = "DX".repeat(16);
        String path = "/3/records/" + id;
        String etag = mvc.perform(post(URI.create("http://localhost:8080" + path))
                        .contentType("application/rdf+xml").content(rdf(id)))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("ETag");
        assertStoredGraph(id, "http://localhost:8080");

        mvc.perform(put(URI.create("https://preview.example.org:8443" + path))
                        .contentType("application/rdf+xml").header("If-Match", etag).content(rdf(id)))
                .andExpect(status().isNoContent());
        assertStoredGraph(id, "https://preview.example.org:8443");

        byte[] output = mvc.perform(get(URI.create("http://another.example:9090" + path)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertGraph(output, id, "https://preview.example.org:8443");
    }

    private void assertStoredGraph(String id, String origin) {
        assertGraph(codec.fromJelly(STORAGE.storedRdf(id), Lang.JSONLD), id, origin);
    }

    private static void assertGraph(byte[] output, String id, String origin) {
        var dataset = RDFParser.source(new ByteArrayInputStream(output)).lang(Lang.JSONLD).toDatasetGraph();
        var record = NodeFactory.createURI(origin + "/records/" + id);
        try {
            assertThat(dataset.listGraphNodes()).toIterable().containsExactly(record);
            var graph = dataset.getGraph(record);
            assertThat(graph.contains(record, NodeFactory.createURI("https://example.org/link"),
                    NodeFactory.createURI(origin + "/items/" + id + "#part"))).isTrue();
            assertThat(graph.contains(record, NodeFactory.createURI("https://example.org/source"),
                    NodeFactory.createURI("https://external.example.org/item/42"))).isTrue();
        } finally {
            dataset.close();
        }
    }

    private static byte[] rdf(String id) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="https://example.org/" xml:base="/">
                  <rdf:Description rdf:about="/records/%s">
                    <ex:link rdf:resource="/items/%s#part"/>
                    <ex:source rdf:resource="https://external.example.org/item/42"/>
                  </rdf:Description>
                </rdf:RDF>
                """.formatted(id, id).getBytes(StandardCharsets.UTF_8);
    }
}
