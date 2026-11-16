/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.rdf.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.ddb.apiv3.api.items.ItemShaclValidator;
import de.ddb.apiv3.api.records.RecordShaclValidator;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validate endpoint policy and shape lifetime without reparsing or storing request data. */
class ShaclRdfValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"records", "items"})
    void eachEndpointRejectsAnEmptyGraphEvenThoughItHasNoSubjects(String collection) {
        RdfValidator validator = endpoint(collection);
        Graph empty = GraphMemFactory.createDefaultGraph();
        try {
            assertThatThrownBy(() -> validator.validate(empty))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(failure.getReason()).contains(collection, "SHACL");
                    });
            assertThat(empty.isClosed()).isFalse();
        } finally {
            empty.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"records", "items"})
    void defaultsAcceptArbitraryNonemptyRdfWithoutInventingDomainRequirements(String collection) {
        RdfValidator validator = endpoint(collection);
        Graph graph = GraphMemFactory.createDefaultGraph();
        graph.add(NodeFactory.createBlankNode(), NodeFactory.createURI("urn:unrelated:predicate"),
                NodeFactory.createLiteralString("arbitrary data"));
        try {
            assertThatCode(() -> validator.validate(graph)).doesNotThrowAnyException();
            assertThat(graph.size()).isEqualTo(1);
            assertThat(graph.isClosed()).isFalse();
        } finally {
            graph.close();
        }
    }

    @Test
    void loadsTheShapeResourceOnlyAtConstructionAndClosesItsInput() {
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        Resource source = new ByteArrayResource(bytes(requiredProperty("record"))) {
            @Override
            public InputStream getInputStream() throws IOException {
                opens.incrementAndGet();
                return new FilterInputStream(super.getInputStream()) {
                    @Override
                    public void close() throws IOException {
                        closes.incrementAndGet();
                        super.close();
                    }
                };
            }
        };
        var validator = new FixtureValidator("records", source);
        assertThat(opens.get()).isEqualTo(1);
        assertThat(closes.get()).isPositive();
        int closesAfterConstruction = closes.get();
        Graph graph = graphWithProperty("record");
        try {
            for (int i = 0; i < 5; i++) {
                validator.validate(graph);
            }
            // Closing the input must not close Shapes' retained backing graph.
            assertThat(opens.get()).isEqualTo(1);
            assertThat(closes.get()).isEqualTo(closesAfterConstruction);
            assertThat(graph.size()).isEqualTo(1);
        } finally {
            graph.close();
        }
    }

    @Test
    void separateValidatorsEnforceTheirOwnCoreConstraintsWithoutCrossApplyingThem() {
        var records = new FixtureValidator("records", resource(requiredProperty("record")));
        var items = new FixtureValidator("items", resource(requiredProperty("item")));
        Graph record = graphWithProperty("record");
        Graph item = graphWithProperty("item");
        try {
            assertThatCode(() -> records.validate(record)).doesNotThrowAnyException();
            assertThatCode(() -> items.validate(item)).doesNotThrowAnyException();
            assertRejected(() -> records.validate(item), "records");
            assertRejected(() -> items.validate(record), "items");
            // Failed validation must neither repair the data nor contaminate the other policy.
            assertThatCode(() -> records.validate(record)).doesNotThrowAnyException();
            assertThatCode(() -> items.validate(item)).doesNotThrowAnyException();
            assertThat(record.size()).isEqualTo(1);
            assertThat(item.size()).isEqualTo(1);
        } finally {
            record.close();
            item.close();
        }
    }

    @Test
    void aMissingShapeResourceFailsWhenTheValidatorIsCreated() {
        assertThatThrownBy(() -> new FixtureValidator("items", new ClassPathResource("shacl/missing-policy.ttl")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot load SHACL shapes for items")
                .hasRootCauseInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<urn:test:s> <urn:test:p> <urn:test:o> .",
            "@prefix sh: <http://www.w3.org/ns/shacl#> . <urn:test:Shape> a sh:NodeShape ."})
    void anEmptyOrUntargetedPolicyCannotSilentlyDisableValidation(String shapes) {
        assertThatThrownBy(() -> new FixtureValidator("items", resource(shapes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot load SHACL shapes for items")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedTurtleFailsWhenTheValidatorIsCreated() {
        assertThatThrownBy(() -> new FixtureValidator("records", resource("@prefix sh: <http://www.w3.org/ns/shacl#> . [")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot load SHACL shapes for records")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void malformedConstraintQueriesFailAtCompilationInsteadOfOnTheFirstUpload() {
        String shapes = """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:Shape> a sh:NodeShape ;
                    sh:targetNode <urn:test:resource> ;
                    sh:sparql [ sh:select "THIS IS NOT A SPARQL QUERY" ] .
                """;
        assertThatThrownBy(() -> new FixtureValidator("items", resource(shapes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot load SHACL shapes for items")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void sharesCompiledEndpointShapesAcrossConcurrentIndependentRequests() throws Exception {
        var records = new RecordShaclValidator();
        var items = new ItemShaclValidator();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = IntStream.range(0, 128).mapToObj(index -> executor.submit(() -> {
                boolean nonempty = index % 2 != 0;
                RdfValidator validator = index % 4 < 2 ? records : items;
                Graph graph = nonempty ? graphWithProperty("unrelated") : GraphMemFactory.createDefaultGraph();
                try {
                    try {
                        validator.validate(graph);
                        return nonempty;
                    } catch (ResponseStatusException failure) {
                        return !nonempty && failure.getStatusCode().equals(HttpStatus.UNPROCESSABLE_CONTENT);
                    }
                } finally {
                    graph.close();
                }
            })).toList();
            for (var result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static void assertRejected(Runnable validation, String collection) {
        assertThatThrownBy(validation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(failure.getReason()).contains(collection);
                });
    }

    private static RdfValidator endpoint(String collection) {
        return "records".equals(collection) ? new RecordShaclValidator() : new ItemShaclValidator();
    }

    private static Graph graphWithProperty(String property) {
        Graph graph = GraphMemFactory.createDefaultGraph();
        graph.add(NodeFactory.createURI("urn:test:resource"), NodeFactory.createURI("urn:test:" + property),
                NodeFactory.createLiteralString("value"));
        return graph;
    }

    private static String requiredProperty(String property) {
        return """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:Shape> a sh:NodeShape ;
                    sh:targetNode <urn:test:resource> ;
                    sh:property [ sh:path <urn:test:%s> ; sh:minCount 1 ] .
                """.formatted(property);
    }

    private static ByteArrayResource resource(String turtle) {
        return new ByteArrayResource(bytes(turtle));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FixtureValidator extends ShaclRdfValidator {
        private FixtureValidator(String collection, Resource shapes) {
            super(collection, shapes);
        }
    }
}
