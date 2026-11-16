/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.documents;

import de.ddb.apiv3.rdf.validation.RdfValidator;
import java.util.Objects;

/** The endpoint-specific choices: public collection, S3 document name, revision header and validator. */
public record RdfDocumentType(String collection, String documentName, String revisionHeader, RdfValidator validator) {
    public RdfDocumentType {
        Objects.requireNonNull(collection);
        Objects.requireNonNull(documentName);
        Objects.requireNonNull(revisionHeader);
        Objects.requireNonNull(validator);
    }

    public String versionedPath(String id) {
        return "/3/" + collection + "/" + id;
    }
}
