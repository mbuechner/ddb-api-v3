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
 */
package de.ddb.apiv3.rdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.ddb.apiv3.storage.StorageException;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import eu.neverblink.jelly.core.proto.v1.PhysicalStreamType;
import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RDFWriterRegistry;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** Exercises the wire formats, not implementation-shaped mocks: RDF in, actual Jelly out. */
class RdfCodecTest {

    private static final String GRAPH =
            "https://api.deutsche-digitale-bibliothek.de/records/J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3";
    private static final String NT = "<https://example.org/s> <https://example.org/p> \"München\"@de .";
    private final RdfCodec codec = new RdfCodec();

    @ParameterizedTest
    @MethodSource("rdfSerializations")
    void acceptsRegisteredSerializationsAndWritesUncompressedDelimitedJelly(String contentType, String source)
            throws Exception {
        byte[] jelly = codec.toJelly(bytes(source), contentType, GRAPH);

        RdfStreamFrame frame = RdfStreamFrame.parseDelimitedFrom(new ByteArrayInputStream(jelly));
        assertThat(frame.getRows().stream().filter(row -> row.hasOptions()).findFirst().orElseThrow()
                .getOptions().getPhysicalType()).isEqualTo(PhysicalStreamType.GRAPHS);
        assertThat(frame.getRows().stream().anyMatch(row -> row.hasGraphStart())).isTrue();
        assertCanonicalGraph(jelly, 1);
    }

    @Test
    void mergesAllInputGraphsWithoutLosingBlankNodeLinksOrLiteralValues() {
        String source = """
                @prefix ex: <https://example.org/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                ex:s ex:p _:shared .
                ex:one { _:shared ex:label "München"@de . }
                ex:two { _:shared ex:count "0042"^^xsd:integer . ex:s ex:p _:shared . }
                """;
        byte[] jelly = codec.toJelly(bytes(source), "application/trig", GRAPH);

        assertCanonicalGraph(jelly, 3);
        var expected = RDFParser.create().fromString("""
                @prefix ex: <https://example.org/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                ex:s ex:p _:shared .
                _:shared ex:label "München"@de ; ex:count "0042"^^xsd:integer .
                """).lang(Lang.TURTLE).toGraph();
        var actual = RDFParser.create().source(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.RDFXML)))
                .lang(Lang.RDFXML).toGraph();
        try {
            assertThat(actual.isIsomorphicWith(expected)).isTrue();
        } finally {
            expected.close();
            actual.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/rdf+xml", "text/turtle", "application/n-triples", "application/trig",
            "application/n-quads", "application/ld+json", "application/rdf+json", "application/trix"})
    void convertsStoredJellyIntoRegisteredGraphAndDatasetSerializations(String mediaType) {
        byte[] jelly = codec.toJelly(bytes(NT), "application/n-triples; charset=UTF-8", GRAPH);
        Lang language = RDFLanguages.contentTypeToLang(mediaType);
        byte[] representation = codec.fromJelly(jelly, language);
        DatasetGraph result = RDFParser.create().source(new ByteArrayInputStream(representation))
                .lang(language).toDatasetGraph();
        try {
            if (RDFWriterRegistry.getWriterDatasetFactory(language) != null) {
                assertThat(result.getGraph(NodeFactory.createURI(GRAPH)).size()).isEqualTo(1);
                assertThat(result.getDefaultGraph().isEmpty()).isTrue();
            } else {
                assertThat(result.getDefaultGraph().size()).isEqualTo(1);
            }
        } finally {
            result.close();
        }
    }

    @Test
    void emptyRdfStillStoresAnExplicitCanonicalGraph() throws Exception {
        byte[] jelly = codec.toJelly(bytes(""), "text/turtle", GRAPH);
        RdfStreamFrame frame = RdfStreamFrame.parseDelimitedFrom(new ByteArrayInputStream(jelly));
        assertThat(frame.getRows().stream().anyMatch(row -> row.hasGraphStart())).isTrue();
        assertThat(frame.getRows().stream().anyMatch(row -> row.hasGraphEnd())).isTrue();
        // RIOT parsers omit empty graph declarations; Jelly's explicit boundaries do retain them.
        assertThat(new String(codec.fromJelly(jelly, Lang.TRIG), StandardCharsets.UTF_8)).contains(GRAPH);
        assertThat(codec.fromJelly(jelly, JellyLanguage.JELLY)).containsExactly(jelly);
    }

    @Test
    void jellyInputAndOutputAreAlsoSupported() {
        byte[] first = codec.toJelly(bytes(NT), "application/n-triples", "https://example.org/foreign");
        byte[] stored = codec.toJelly(first, RdfCodec.JELLY_CONTENT_TYPE, GRAPH);
        assertCanonicalGraph(stored, 1);
        assertThat(codec.fromJelly(stored, JellyLanguage.JELLY)).containsExactly(stored);
    }

    @Test
    void splitsLargeGraphsIntoDecodableDelimitedFrames() throws Exception {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 1500; index++) {
            source.append("<https://example.org/s").append(index).append("> <https://example.org/p> \"value\" .\n");
        }
        byte[] jelly = codec.toJelly(bytes(source.toString()), "application/n-triples", GRAPH);
        ByteArrayInputStream input = new ByteArrayInputStream(jelly);
        int frames = 0;
        while (input.available() > 0) {
            RdfStreamFrame.parseDelimitedFrom(input);
            frames++;
        }
        assertThat(frames).isGreaterThan(1);
        assertCanonicalGraph(jelly, 1500);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"application/json", "application/octet-stream", "*/*", "broken", "text/plain"})
    void rejectsMediaTypesWithoutARegisteredRdfParser(String mediaType) {
        assertThatThrownBy(() -> codec.toJelly(bytes(NT), mediaType, GRAPH))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rejectsMalformedRdfEvenAfterACompleteFirstStatement() {
        assertThatThrownBy(() -> codec.toJelly(bytes(NT + "\n<https://example.org/broken>"),
                "application/n-triples", GRAPH)).isInstanceOf(InvalidRdfException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not Jelly", "\u001f\u008bcompressed bytes"})
    void storedCorruptionIsAStorageFailure(String corrupt) {
        assertThatThrownBy(() -> codec.fromJelly(bytes(corrupt), Lang.TRIG))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void writerLimitationsBecomeNotAcceptable() {
        byte[] jelly = codec.toJelly(bytes("<https://example.org/s> <https://example.org/123> \"value\" ."),
                "application/n-triples", GRAPH);
        assertThatThrownBy(() -> codec.fromJelly(jelly, Lang.RDFXML))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
    }

    @Test
    void rdfJsonCannotSilentlyDropTripleTerms() {
        var graph = GraphMemFactory.createDefaultGraph();
        var subject = NodeFactory.createURI("https://example.org/s");
        var predicate = NodeFactory.createURI("https://example.org/p");
        graph.add(Triple.create(subject, predicate, NodeFactory.createTripleTerm(subject, predicate, subject)));
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try {
            RDFWriter.create().source(graph).lang(JellyLanguage.JELLY).output(source);
        } finally {
            graph.close();
        }
        byte[] jelly = codec.toJelly(source.toByteArray(), RdfCodec.JELLY_CONTENT_TYPE, GRAPH);
        assertThatThrownBy(() -> codec.fromJelly(jelly, Lang.RDFJSON))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
    }

    @Test
    void jsonLdCannotFetchContextsOrImports() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/context", exchange -> {
            fetches.incrementAndGet();
            byte[] context = bytes("{\"@context\":{\"p\":\"https://example.org/p\"}}");
            exchange.getResponseHeaders().add("Content-Type", "application/ld+json");
            exchange.sendResponseHeaders(200, context.length);
            exchange.getResponseBody().write(context);
            exchange.close();
        });
        server.start();
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/context";
            for (String context : List.of("\"" + remote + "\"", "{\"@import\":\"" + remote + "\"}")) {
                String source = "{\"@context\":" + context + ",\"@id\":\"https://example.org/s\",\"p\":\"value\"}";
                assertThatThrownBy(() -> codec.toJelly(bytes(source), "application/ld+json", GRAPH))
                        .isInstanceOf(InvalidRdfException.class);
            }
            assertThat(fetches).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void negotiatesQualityAndSpecificExclusions() {
        assertThat(codec.outputLanguage(List.of())).isEqualTo(Lang.TRIG);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("*/*"))).isEqualTo(Lang.TRIG);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/rdf+xml;q=0.2,text/turtle;q=0.8")))
                .isEqualTo(Lang.TURTLE);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("application/trig;q=0,*/*;q=1")))
                .isNotEqualTo(Lang.TRIG);
        assertThat(codec.outputLanguage(MediaType.parseMediaTypes("text/*;q=0,text/turtle;q=0.5,*/*;q=0")))
                .isEqualTo(Lang.TURTLE);
        for (String accept : List.of("*/*;q=0", "text/html", "application/rdf+xml;q=0")) {
            assertThatThrownBy(() -> codec.outputLanguage(MediaType.parseMediaTypes(accept)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
        }
    }

    @Test
    void negotiatesAliasesAndOnlySupportedMediaTypeParameters() {
        MediaType alias = MediaType.parseMediaType("application/trix+xml");
        assertThat(codec.outputMediaType(List.of(alias))).isEqualTo(alias);
        assertThat(codec.outputLanguage(List.of(alias))).isEqualTo(Lang.TRIX);
        assertThat(codec.outputMediaType(MediaType.parseMediaTypes("text/turtle;charset=UTF-8")))
                .isEqualTo(MediaType.parseMediaType("text/turtle;charset=UTF-8"));
        for (String unsupported : List.of("text/turtle;charset=UTF-16", "text/turtle;profile=unavailable",
                "application/ld+json;profile=unavailable")) {
            assertThatThrownBy(() -> codec.outputMediaType(MediaType.parseMediaTypes(unsupported)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE));
        }
    }

    private void assertCanonicalGraph(byte[] jelly, int expectedSize) {
        DatasetGraph result = RDFParser.create().source(new ByteArrayInputStream(codec.fromJelly(jelly, Lang.TRIG)))
                .lang(Lang.TRIG).toDatasetGraph();
        try {
            assertThat(result.listGraphNodes()).toIterable().containsExactly(NodeFactory.createURI(GRAPH));
            assertThat(result.getDefaultGraph().isEmpty()).isTrue();
            assertThat(result.getGraph(NodeFactory.createURI(GRAPH)).size()).isEqualTo(expectedSize);
        } finally {
            result.close();
        }
    }

    private static Stream<Arguments> rdfSerializations() {
        return Stream.of(
                Arguments.of("application/rdf+xml", """
                        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="https://example.org/">
                          <rdf:Description rdf:about="https://example.org/s"><ex:p xml:lang="de">München</ex:p></rdf:Description>
                        </rdf:RDF>
                        """),
                Arguments.of("text/turtle", NT),
                Arguments.of("application/n-triples", NT),
                Arguments.of("application/trig", "<https://example.org/foreign> { " + NT + " }"),
                Arguments.of("application/n-quads", NT.substring(0, NT.length() - 1) + "<https://example.org/foreign> ."),
                Arguments.of("application/ld+json", """
                        {"@context":{"p":"https://example.org/p"},"@id":"https://example.org/s",
                         "p":{"@value":"München","@language":"de"}}
                        """),
                Arguments.of("application/rdf+json", """
                        {"https://example.org/s":{"https://example.org/p":[{"type":"literal","value":"München","lang":"de"}]}}
                        """),
                Arguments.of("application/trix", """
                        <TriX xmlns="http://www.w3.org/2004/03/trix/trix-1/"><graph>
                          <triple><uri>https://example.org/s</uri><uri>https://example.org/p</uri>
                          <plainLiteral xml:lang="de">München</plainLiteral></triple>
                        </graph></TriX>
                        """));
    }

    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
