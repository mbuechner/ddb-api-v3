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

package de.ddb.apiv3.rdf;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonLdRecordWriterTest {

    private static final String GRAPH = "https://example.org/records/EXAMPLE";

    @Test
    void emitsAnInlineContextAndNamedGraphWithoutLosingRdfTerms() {
        var source = RDFParser.create().fromString("""
                @prefix custom: <https://example.org/vocab/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                <https://example.org/records/EXAMPLE> {
                    custom:record a custom:Record ; custom:link _:shared ; custom:count "0042"^^xsd:integer .
                    _:shared custom:label "München"@de ; custom:link custom:record .
                }
                """).lang(Lang.TRIG).toDatasetGraph();
        try {
            byte[] output = JsonLdRecordWriter.write(source);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var document = reader.readObject();
                assertThat(document.keySet()).containsExactlyInAnyOrder("@context", "@id", "@graph");
                assertThat(document.getJsonObject("@context")).isNotEmpty();
                assertThat(document.getJsonObject("@context").getString("custom"))
                        .isEqualTo("https://example.org/vocab/");
                assertThat(document.getString("@id")).isEqualTo(GRAPH);
                assertThat(document.getJsonArray("@graph")).hasSize(2);
            }
            assertThat(new String(output, StandardCharsets.UTF_8)).doesNotContain("\n")
                    .contains("0042", "\"@language\":\"de\"")
                    .contains("\"custom:link\"")
                    .doesNotContain("\"https://example.org/vocab/link\"");
            assertGraphRoundTrip(output, source);
        } finally {
            source.close();
        }
    }

    @Test
    void anOriginalNs1IsRetainedWithoutInventingOtherPrefixes() {
        var source = RDFParser.create().fromString("""
                @prefix ns1: <https://z.example.org/vocab/> .
                <https://example.org/records/EXAMPLE> {
                    <https://a.example.org/s> <https://a.example.org/p> ns1:object .
                }
                """).lang(Lang.TRIG).toDatasetGraph();
        try {
            byte[] output = JsonLdRecordWriter.write(source);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var context = reader.readObject().getJsonObject("@context");
                assertThat(context.getString("ns1"))
                        .isEqualTo("https://z.example.org/vocab/");
                assertThat(context.keySet()).containsExactly("ns1");
            }
            assertThat(new String(output, StandardCharsets.UTF_8)).contains("https://a.example.org/p");
            assertGraphRoundTrip(output, source);
        } finally {
            source.close();
        }
    }

    @Test
    void anOriginalNamespaceDoesNotNeedATrailingSlashOrHash() {
        var source = RDFParser.create().fromString("""
                @prefix custom: <https://example.org/vocab/p> .
                <https://example.org/records/EXAMPLE> { custom:Subject custom:Property "value" . }
                """).lang(Lang.TRIG).toDatasetGraph();
        try {
            byte[] output = JsonLdRecordWriter.write(source);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var context = reader.readObject().getJsonObject("@context");
                assertThat(context.getString("custom"))
                        .isEqualTo("https://example.org/vocab/p");
            }
            assertGraphRoundTrip(output, source);
        } finally {
            source.close();
        }
    }

    @Test
    void recordsWithoutPrefixDeclarationsKeepFullIrisAndAnEmptyContext() {
        var dataset = DatasetGraphFactory.createGeneral();
        var graph = GraphMemFactory.createDefaultGraph();
        graph.add(Triple.create(NodeFactory.createURI("https://example.org/s"),
                NodeFactory.createURI("https://example.org/p"), NodeFactory.createLiteralString("value")));
        dataset.addGraph(NodeFactory.createURI(GRAPH), graph);
        try {
            byte[] output = JsonLdRecordWriter.write(dataset);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var context = reader.readObject().getJsonObject("@context");
                assertThat(context).isEmpty();
            }
            assertThat(new String(output, StandardCharsets.UTF_8)).contains("https://example.org/p");
            assertGraphRoundTrip(output, dataset);
        } finally {
            dataset.close();
        }
    }

    @Test
    void theOriginalDefaultPrefixBecomesTheJsonLdVocabulary() {
        var source = RDFParser.create().fromString("""
                @prefix : <https://example.org/vocab/> .
                <https://example.org/records/EXAMPLE> { :record :label "value" . }
                """).lang(Lang.TRIG).toDatasetGraph();
        try {
            byte[] output = JsonLdRecordWriter.write(source);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var context = reader.readObject().getJsonObject("@context");
                assertThat(context.getString("@vocab")).isEqualTo("https://example.org/vocab/");
                assertThat(context).doesNotContainKey("");
            }
            assertGraphRoundTrip(output, source);
        } finally {
            source.close();
        }
    }

    @Test
    void anEmptyGraphStillHasAnExplicitNameAndGraphArray() {
        var dataset = DatasetGraphFactory.createGeneral();
        dataset.addGraph(NodeFactory.createURI(GRAPH), GraphMemFactory.createDefaultGraph());
        try {
            byte[] output = JsonLdRecordWriter.write(dataset);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var document = reader.readObject();
                assertThat(document.getString("@id")).isEqualTo(GRAPH);
                assertThat(document.getJsonArray("@graph")).isEmpty();
                assertThat(document.getJsonObject("@context")).isNotNull();
            }
        } finally {
            dataset.close();
        }
    }

    @ParameterizedTest
    @MethodSource("ambiguousNamespaceHints")
    void namespaceHintsNeverChangeRdfTermsOrMakeValidRdfUnrepresentable(
            Map<String, String> hints, boolean fallsBackToFullIris, boolean retainsHints) {
        var dataset = RDFParser.create().fromString("""
                @prefix custom: <https://example.org/vocab/> .
                <https://example.org/records/EXAMPLE> {
                    <urn:record:x> a custom:Record ; custom:link <http://example.org/item> .
                    custom:record custom:link custom:item .
                }
                """).lang(Lang.TRIG).toDatasetGraph();
        try {
            hints.forEach(dataset.prefixes()::add);
            byte[] output = JsonLdRecordWriter.write(dataset);
            assertGraphRoundTrip(output, dataset);
            try (var reader = Json.createReader(new ByteArrayInputStream(output))) {
                var context = reader.readObject().getJsonObject("@context");
                if (fallsBackToFullIris) {
                    assertThat(context).isEmpty();
                } else {
                    assertThat(context.getString("custom")).isEqualTo("https://example.org/vocab/");
                    if (retainsHints) {
                        hints.forEach((prefix, iri) -> assertThat(context.getString(prefix)).isEqualTo(iri));
                    } else {
                        assertThat(context.keySet()).doesNotContainAnyElementsOf(hints.keySet());
                    }
                    assertThat(new String(output, StandardCharsets.UTF_8)).contains("custom:link");
                }
            }
        } finally {
            dataset.close();
        }
    }

    static Stream<Arguments> ambiguousNamespaceHints() {
        return Stream.of(
                Arguments.of(Map.of("_", "https://example.org/vocab/"), false, false),
                Arguments.of(Map.of("urn", "urn:ns:"), false, false),
                Arguments.of(Map.of("https", "https://example.org/"), false, true),
                Arguments.of(Map.of("urn", "https://example.org/"), true, false),
                Arguments.of(Map.of("http", "urn:ns:", "urn", "http://example.org/"), true, false),
                Arguments.of(Map.of("alpha", "beta:ns:", "beta", "alpha:ns:"), true, false));
    }

    @Test
    void anEmptyGraphDoesNotProcessUnusedAmbiguousNamespaceHints() {
        var dataset = DatasetGraphFactory.createGeneral();
        dataset.addGraph(NodeFactory.createURI(GRAPH), GraphMemFactory.createDefaultGraph());
        dataset.prefixes().add("urn", "urn:ns:");
        dataset.prefixes().add("_", "https://example.org/vocab/");
        try (var reader = Json.createReader(new ByteArrayInputStream(JsonLdRecordWriter.write(dataset)))) {
            var document = reader.readObject();
            assertThat(document.getString("@id")).isEqualTo(GRAPH);
            assertThat(document.getJsonArray("@graph")).isEmpty();
            assertThat(document.getJsonObject("@context")).isEmpty();
        } finally {
            dataset.close();
        }
    }

    private static void assertGraphRoundTrip(byte[] output, DatasetGraph expected) {
        var restored = RDFParser.create().source(new ByteArrayInputStream(output))
                .lang(Lang.JSONLD).toDatasetGraph();
        try {
            assertThat(restored.getDefaultGraph().isEmpty()).isTrue();
            assertThat(restored.listGraphNodes()).toIterable().containsExactly(NodeFactory.createURI(GRAPH));
            assertThat(restored.getGraph(NodeFactory.createURI(GRAPH))
                    .isIsomorphicWith(expected.getGraph(NodeFactory.createURI(GRAPH)))).isTrue();
        } finally {
            restored.close();
        }
    }
}
