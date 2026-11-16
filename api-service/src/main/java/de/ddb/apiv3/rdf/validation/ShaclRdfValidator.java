/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.rdf.validation;

import de.ddb.apiv3.config.RequestTiming;
import java.io.IOException;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Parse endpoint policy once; keep its shapes read-only and each upload's data/validation state local. */
public abstract class ShaclRdfValidator implements RdfValidator {
    private final Shapes shapes;
    private final String collection;

    protected ShaclRdfValidator(String collection, String classpath) {
        this(collection, new ClassPathResource(classpath));
    }

    protected ShaclRdfValidator(String collection, Resource resource) {
        this.collection = collection;
        Graph graph = GraphMemFactory.createDefaultGraph();
        try (var input = resource.getInputStream()) {
            // Explicit stream/format: Turtle and Shapes.parse(Graph) do not follow remote imports.
            RDFDataMgr.read(graph, input, Lang.TURTLE);
            shapes = Shapes.parse(graph);
            if (shapes.getTargetShapes().isEmpty()) {
                // Valid Turtle without an active policy must not silently disable endpoint validation.
                throw new IllegalArgumentException("A SHACL policy must contain at least one targeted shape");
            }
        } catch (IOException | RuntimeException exception) {
            graph.close();
            throw new IllegalStateException("Cannot load SHACL shapes for " + collection, exception);
        }
        // Shapes retains its backing graph. Keep it alive and private/read-only for the bean lifetime.
    }

    @Override
    public final void validate(Graph graph) {
        try (var timing = RequestTiming.start("shacl_validate")) {
            if (!ShaclValidator.get().validate(shapes, graph).conforms()) {
                // Do not echo uploaded RDF or serialize a potentially large report into logs/errors.
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "RDF data does not conform to the " + collection + " SHACL shapes");
            }
        }
    }
}
