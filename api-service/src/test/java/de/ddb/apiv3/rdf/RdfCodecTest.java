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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.ddb.apiv3.storage.StorageException;
import eu.neverblink.jelly.convert.jena.JenaConverterFactory;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import eu.neverblink.jelly.core.JellyOptions;
import eu.neverblink.jelly.core.RdfEncoder;
import eu.neverblink.jelly.core.proto.v1.PhysicalStreamType;
import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import jakarta.json.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** XML in, one named Jelly graph in the bucket, three actual wire formats out. */
class RdfCodecTest {

    private static final String GRAPH = "https://api.deutsche-digitale-bibliothek.de/records/" + "A".repeat(32);
    private static final String XML = xml("""
            <rdf:Description rdf:about="https://example.org/s"><ex:p xml:lang="de">München</ex:p></rdf:Description>
            """);
    private final RdfCodec codec = new RdfCodec();

    @Test
    void neverFetchesExternalXmlEntitiesOrDtds() throws Exception {
        var fetches = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            fetches.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String external = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            codec.toJelly(bytes("<!DOCTYPE rdf:RDF [<!ENTITY remote SYSTEM '" + external + "'>]>"
                    + xml("<rdf:Description rdf:about='https://example.org/s'><ex:p>&remote;</ex:p></rdf:Description>")), GRAPH);
            codec.toJelly(bytes("<!DOCTYPE rdf:RDF SYSTEM '" + external + "'>"
                    + xml("<rdf:Description rdf:about='https://example.org/s'><ex:p>plain</ex:p></rdf:Description>")), GRAPH);
            codec.toJelly(bytes("<!DOCTYPE rdf:RDF [<!ENTITY % remote SYSTEM '" + external + "'>%remote;]>"
                    + xml("<rdf:Description rdf:about='https://example.org/s'><ex:p>plain</ex:p></rdf:Description>")), GRAPH);
            assertThat(fetches.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeXmlLimitsRejectEntityAmplificationBeforeItBecomesAnRdfGraph() {
        String source = "<!DOCTYPE rdf:RDF [<!ENTITY chunk '" + "x".repeat(512)
                + "'><!ENTITY expanded '" + "&chunk;".repeat(256) + "'>]>"
                + xml("<rdf:Description rdf:about='https://example.org/s'><ex:p>&expanded;</ex:p></rdf:Description>");
        assertThat(source.length()).isLessThan(4096);
        assertInvalidXml(source);
    }

    @Test
    void writesRdfXmlAsUncompressedDelimitedJellyWithTheCanonicalGraphName() throws Exception {
        byte[] jelly = codec.toJelly(bytes(XML), GRAPH);

        RdfStreamFrame frame = RdfStreamFrame.parseDelimitedFrom(new ByteArrayInputStream(jelly));
        var options = frame.getRows().stream().filter(row -> row.hasOptions()).findFirst().orElseThrow().getOptions();
        assertThat(options.getPhysicalType()).isEqualTo(PhysicalStreamType.QUADS);
        assertThat(options.getVersion()).isEqualTo(3);
        assertThat(frame.getColumns()).isNotNull();
        assertThat(frame.getColumns().getRowCount()).isEqualTo(1);
        assertThat(frame.getRows().stream().anyMatch(row -> row.hasGraphStart() || row.hasGraphEnd())).isFalse();
        assertCanonicalGraph(jelly, 1);
    }

    @Test
    void preservesBlankNodeLinksAndLiteralLexicalValues() {
        String source = xml("""
                <rdf:Description rdf:about="https://example.org/s"><ex:detail rdf:nodeID="shared"/></rdf:Description>
                <rdf:Description rdf:nodeID="shared">
                  <ex:label xml:lang="de">München</ex:label>
                  <ex:count rdf:datatype="http://www.w3.org/2001/XMLSchema#integer">0042</ex:count>
                </rdf:Description>
                """);
        byte[] jelly = codec.toJelly(bytes(source), GRAPH);

        assertCanonicalGraph(jelly, 3);
        var expected = RDFParser.create().fromString(source).lang(Lang.RDFXML).toGraph();
        var actual = RDFParser.source(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.RDFXML)))
                .lang(Lang.RDFXML).toGraph();
        try {
            assertThat(actual.isIsomorphicWith(expected)).isTrue();
        } finally {
            expected.close();
            actual.close();
        }
    }

    @Test
    void resolvesRelativeIrisAgainstTheCanonicalGraph() {
        byte[] jelly = codec.toJelly(bytes(xml("""
                <rdf:Description rdf:about="#root"><ex:link rdf:resource="#linked"/></rdf:Description>
                """)), GRAPH);
        DatasetGraph result = readJelly(jelly);
        try {
            assertThat(result.getGraph(NodeFactory.createURI(GRAPH)).contains(
                    NodeFactory.createURI(GRAPH + "#root"), NodeFactory.createURI("https://example.org/link"),
                    NodeFactory.createURI(GRAPH + "#linked"))).isTrue();
        } finally {
            result.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/rdf+xml", "application/ld+json", "application/x-jelly-rdf"})
    void convertsStoredJellyIntoTheThreeApiOutputSerializations(String mediaType) {
        byte[] jelly = codec.toJelly(bytes(XML), GRAPH);
        Lang language = RDFLanguages.contentTypeToLang(mediaType);
        DatasetGraph result = RDFParser.source(new ByteArrayInputStream(codec.fromJelly(jelly, language)))
                .lang(language).toDatasetGraph();
        try {
            if (language.equals(Lang.RDFXML)) {
                assertThat(result.getDefaultGraph().size()).isEqualTo(1);
            } else {
                assertThat(result.listGraphNodes()).toIterable().containsExactly(NodeFactory.createURI(GRAPH));
                assertThat(result.getGraph(NodeFactory.createURI(GRAPH)).size()).isEqualTo(1);
                assertThat(result.getDefaultGraph().isEmpty()).isTrue();
            }
        } finally {
            result.close();
        }
    }

    @Test
    void rejectsEmptyRdfXmlBecauseJelly12CannotRetainItsGraphName() {
        assertInvalidXml(xml(""));
    }

    @Test
    void preservesDeclaredNamespacesForCompactJsonLdAndTypedXml() {
        String source = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:custom="https://example.org/vocab/" xmlns:types="https://example.org/types/"
                         xmlns:unused="https://unused.example.org/">
                  <types:Record rdf:about="https://example.org/record"><custom:label>value</custom:label></types:Record>
                </rdf:RDF>
                """;
        byte[] jelly = codec.toJelly(bytes(source), GRAPH);
        try (var reader = Json.createReader(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.JSONLD)))) {
            var document = reader.readObject();
            var context = document.getJsonObject("@context");
            assertThat(context.getString("custom")).isEqualTo("https://example.org/vocab/");
            assertThat(context.getString("types")).isEqualTo("https://example.org/types/");
            assertThat(context.getString("unused")).isEqualTo("https://unused.example.org/");
            assertThat(document.getJsonArray("@graph").getJsonObject(0).getString("@type")).isEqualTo("types:Record");
        }
        assertThat(new String(codec.fromJelly(jelly, Lang.RDFXML), StandardCharsets.UTF_8))
                .contains("xmlns:custom=", "xmlns:types=", "types:Record");
    }

    @Test
    void storedNamespaceHintsCannotBecomeRemoteJsonLdContextDirectives() {
        byte[] untrusted = storedJelly(encoder -> {
            encoder.handleNamespace("@import", NodeFactory.createURI("https://example.org/context"));
            encoder.handleQuad(NodeFactory.createURI("https://example.org/s"),
                    NodeFactory.createURI("https://example.org/p"), NodeFactory.createLiteralString("value"),
                    NodeFactory.createURI(GRAPH));
        });
        assertInvalidStoredJelly(untrusted);
    }

    @Test
    void preservesAllTriplesAcrossMultipleJellyFrames() throws Exception {
        var descriptions = new StringBuilder();
        for (int index = 0; index < 1500; index++) {
            descriptions.append("<rdf:Description rdf:about=\"https://example.org/s").append(index)
                    .append("\"><ex:p>value</ex:p></rdf:Description>");
        }
        byte[] jelly = codec.toJelly(bytes(xml(descriptions.toString())), GRAPH);
        var input = new ByteArrayInputStream(jelly);
        int frameCount = 0;
        int statementCount = 0;
        for (var frame = RdfStreamFrame.parseDelimitedFrom(input); frame != null;
                frame = RdfStreamFrame.parseDelimitedFrom(input)) {
            frameCount++;
            statementCount += frame.getColumns().getRowCount();
        }
        assertThat(frameCount).isGreaterThan(1);
        assertThat(statementCount).isEqualTo(1500);
        assertCanonicalGraph(codec.fromJelly(jelly, JellyLanguage.JELLY), 1500);
        var restored = RDFParser.source(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.RDFXML)))
                .lang(Lang.RDFXML).toGraph();
        try {
            assertThat(restored.size()).isEqualTo(1500);
        } finally {
            restored.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not XML", "<rdf:RDF", "{\"@id\":\"https://example.org/s\"}"})
    void rejectsBodiesThatAreNotRdfXml(String source) {
        assertInvalidXml(source);
    }

    @Test
    void rejectsMalformedXmlEvenAfterACompleteFirstStatement() {
        assertInvalidXml(XML + "<broken>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not Jelly", "\u001f\u008bcompressed bytes"})
    void storedCorruptionIsAStorageFailure(String corrupt) {
        assertInvalidStoredJelly(bytes(corrupt));
    }

    @Test
    void storedObjectsMustContainExactlyOneNamedGraph() {
        byte[] untrusted = storedJelly(encoder -> {
            encoder.handleQuad(NodeFactory.createURI("https://example.org/s"),
                    NodeFactory.createURI("https://example.org/p"), NodeFactory.createLiteralString("value"),
                    NodeFactory.createURI(GRAPH));
            encoder.handleQuad(NodeFactory.createURI("https://example.org/s"),
                    NodeFactory.createURI("https://example.org/p"), NodeFactory.createLiteralString("value"),
                    NodeFactory.createURI("https://example.org/another"));
        });
        assertInvalidStoredJelly(untrusted);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storedGraphNamesMustBeIrisAndNeverTheDefaultGraph(boolean blankNode) {
        byte[] untrusted = storedJelly(encoder -> encoder.handleQuad(NodeFactory.createURI("https://example.org/s"),
                NodeFactory.createURI("https://example.org/p"), NodeFactory.createLiteralString("value"),
                blankNode ? NodeFactory.createBlankNode("graph") : null));
        assertInvalidStoredJelly(untrusted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"header-only", "missing-predicates", "truncated"})
    void rejectsIncompleteStoredJellyFrames(String corruption) throws Exception {
        byte[] valid = codec.toJelly(bytes(XML), GRAPH);
        var frame = RdfStreamFrame.parseDelimitedFrom(new ByteArrayInputStream(valid));
        byte[] damaged = switch (corruption) {
            case "header-only" -> RdfStreamFrame.newInstance().addRows(frame.getRows().iterator().next())
                    .toByteArrayDelimited();
            case "missing-predicates" -> frame.clone().setColumns(frame.getColumns().clone().setPredicates(null))
                    .toByteArrayDelimited();
            case "truncated" -> Arrays.copyOf(valid, valid.length - 1);
            default -> throw new IllegalArgumentException(corruption);
        };
        assertInvalidStoredJelly(damaged);
    }

    @Test
    void sharedCodecKeepsConcurrentConversionsIsolated() throws Exception {
        final var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var conversions = IntStream.range(0, 64).mapToObj(index -> executor.submit(() -> {
                String graphName = GRAPH + "/" + index;
                String prefix = "p" + index;
                String namespace = "https://example.org/vocab/" + index + "/";
                String source = """
                        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:%s="%s">
                          <rdf:Description rdf:about="https://example.org/resource">
                            <rdf:type rdf:resource="https://example.org/types/%d/Record"/>
                            <%s:value>request %d</%s:value>
                          </rdf:Description>
                        </rdf:RDF>
                        """.formatted(prefix, namespace, index, prefix, index, prefix);
                byte[] jelly = codec.toJelly(bytes(source), graphName);

                byte[] xml = codec.fromJelly(jelly, Lang.RDFXML);
                var expected = RDFParser.create().fromString(source).lang(Lang.RDFXML).toGraph();
                var actual = RDFParser.source(new ByteArrayInputStream(xml)).lang(Lang.RDFXML).toGraph();
                try {
                    assertThat(actual.isIsomorphicWith(expected)).isTrue();
                } finally {
                    expected.close();
                    actual.close();
                }

                try (var reader = Json.createReader(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.JSONLD)))) {
                    var document = reader.readObject();
                    assertThat(document.getString("@id")).isEqualTo(graphName);
                    assertThat(document.getJsonObject("@context").getString(prefix)).isEqualTo(namespace);
                    assertThat(document.getJsonArray("@graph").getJsonObject(0).getString(prefix + ":value"))
                            .isEqualTo("request " + index);
                }
                assertThat(codec.fromJelly(jelly, JellyLanguage.JELLY)).containsExactly(jelly);
            })).toList();
            for (var conversion : conversions) {
                conversion.get(30, TimeUnit.SECONDS);
            }
        } finally {
            // Executor.close() could wait forever on a broken task; bound failure cleanup too.
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void unrepresentableStoredDataBecomesNotAcceptable() {
        byte[] jelly = storedJelly(encoder -> {
            encoder.handleQuad(NodeFactory.createURI("https://example.org/s"),
                    NodeFactory.createURI("https://example.org/123"), NodeFactory.createLiteralString("value"),
                    NodeFactory.createURI(GRAPH));
        });
        assertThatThrownBy(() -> codec.fromJelly(jelly, Lang.RDFXML))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/turtle", "application/trig", "application/n-quads", "application/n-triples",
            "application/rdf+json", "application/trix", "text/n3", "application/rdf+protobuf"})
    void rejectsRemovedAndOtherOutputFormats(String mediaType) {
        byte[] jelly = codec.toJelly(bytes(XML), GRAPH);
        assertThatThrownBy(() -> codec.outputMediaType(MediaType.parseMediaTypes(mediaType)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
        assertThatThrownBy(() -> codec.fromJelly(jelly, RDFLanguages.contentTypeToLang(mediaType)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
    }

    @Test
    void defaultsToJsonLdForMissingOrWildcardAccept() {
        assertThat(codec.outputLanguage(null)).isEqualTo(Lang.JSONLD);
        assertThat(codec.outputLanguage(List.of())).isEqualTo(Lang.JSONLD);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("*/*"))).isEqualTo(Lang.JSONLD);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/*"))).isEqualTo(Lang.JSONLD);
    }

    @Test
    void negotiatesQualitySpecificExclusionsAndClientOrderForTies() {
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes(
                "application/rdf+xml;q=0.2,application/ld+json;q=0.8"))).isEqualTo(Lang.JSONLD);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/ld+json;q=0,*/*;q=1")))
                .isIn(Lang.RDFXML, JellyLanguage.JELLY);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/*;q=0,application/rdf+xml;q=0.8")))
                .isEqualTo(Lang.RDFXML);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/rdf+xml,application/ld+json")))
                .isEqualTo(Lang.RDFXML);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/ld+json,application/rdf+xml")))
                .isEqualTo(Lang.JSONLD);
        for (String accept : List.of("*/*;q=0", "text/html", "application/rdf+xml;q=0")) {
            assertThatThrownBy(() -> codec.outputLanguage(MediaType.parseMediaTypes(accept)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
        }
    }

    @Test
    void negotiatesCanonicalFormatsAndOnlySupportedMediaTypeParameters() {
        MediaType rdfXml = MediaType.parseMediaType("APPLICATION/RDF+XML");
        assertThat(codec.outputMediaType(List.of(rdfXml))).isEqualTo(MediaType.parseMediaType("application/rdf+xml"));
        assertThat(codec.outputLanguage(List.of(rdfXml))).isEqualTo(Lang.RDFXML);
        assertThat(codec.outputMediaType(MediaType.parseMediaTypes("application/ld+json;charset=UTF-8")))
                .isEqualTo(MediaType.parseMediaType("application/ld+json;charset=UTF-8"));
        for (String unsupported : List.of("application/ld+json;charset=UTF-16", "application/ld+json;profile=unavailable",
                "application/rdf+xml;profile=unavailable")) {
            assertThatThrownBy(() -> codec.outputMediaType(MediaType.parseMediaTypes(unsupported)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
        }
    }

    private void assertInvalidStoredJelly(byte[] jelly) {
        // Both materializing conversions and Jelly passthrough reject corrupt storage identically.
        for (Lang language : List.of(Lang.JSONLD, JellyLanguage.JELLY)) {
            assertThatThrownBy(() -> codec.fromJelly(jelly, language))
                    .as("Stored Jelly validation for %s", language)
                    .isInstanceOf(StorageException.class);
        }
    }

    private void assertInvalidXml(String source) {
        assertThatThrownBy(() -> codec.toJelly(bytes(source), GRAPH))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    private static void assertCanonicalGraph(byte[] jelly, int expectedSize) {
        DatasetGraph result = readJelly(jelly);
        try {
            assertThat(result.listGraphNodes()).toIterable().containsExactly(NodeFactory.createURI(GRAPH));
            assertThat(result.getDefaultGraph().isEmpty()).isTrue();
            assertThat(result.getGraph(NodeFactory.createURI(GRAPH)).size()).isEqualTo(expectedSize);
        } finally {
            result.close();
        }
    }

    private static DatasetGraph readJelly(byte[] jelly) {
        return RDFParser.source(new ByteArrayInputStream(jelly)).lang(JellyLanguage.JELLY).toDatasetGraph();
    }

    private static byte[] storedJelly(Consumer<RdfEncoder<Node>> statements) {
        var output = new ByteArrayOutputStream();
        var options = JellyOptions.SMALL_STRICT.clone().setPhysicalType(PhysicalStreamType.QUADS);
        var encoder = JenaConverterFactory.getInstance().encoder(RdfEncoder.Params.of(options, 1024,
                frame -> output.writeBytes(frame.toByteArrayDelimited())));
        statements.accept(encoder);
        encoder.flush();
        return output.toByteArray();
    }

    private static String xml(String descriptions) {
        return "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:ex=\"https://example.org/\">"
                + descriptions + "</rdf:RDF>";
    }

    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
