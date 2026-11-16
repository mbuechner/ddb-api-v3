/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.rdf.validation;

import org.apache.jena.graph.Graph;

/** Validate the already parsed upload graph; never retain or mutate request data. */
@FunctionalInterface
public interface RdfValidator {
    void validate(Graph graph);
}
