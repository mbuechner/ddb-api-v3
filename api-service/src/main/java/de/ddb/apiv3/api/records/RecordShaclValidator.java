/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.api.records;

import de.ddb.apiv3.rdf.validation.ShaclRdfValidator;
import org.springframework.stereotype.Component;

/** Records own their validation policy; changing it does not affect items. */
@Component
public final class RecordShaclValidator extends ShaclRdfValidator {
    public RecordShaclValidator() {
        super("records", "shacl/records.ttl");
    }
}
