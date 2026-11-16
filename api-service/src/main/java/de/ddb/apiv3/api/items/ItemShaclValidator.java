/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.api.items;

import de.ddb.apiv3.rdf.validation.ShaclRdfValidator;
import org.springframework.stereotype.Component;

/** Items own their validation policy; changing it does not affect records. */
@Component
public final class ItemShaclValidator extends ShaclRdfValidator {
    public ItemShaclValidator() {
        super("items", "shacl/items.ttl");
    }
}
