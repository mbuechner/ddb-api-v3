/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package de.ddb.apiv3.rdf;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.context.ActiveContext;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Compacts ordinary Jena-expanded records with namespace-only contexts.
 *
 * <p>The complete document must pass preflight; lists, quoted triples and richer context rules
 * remain Titanium's job. Context and memoization maps belong to one conversion, not a record cache.
 */
final class NamespaceJsonLdCompactor {

    // Share provider discovery, never mutable JSON builders or request data.
    private static final JsonProvider JSON = JsonProvider.provider();

    private final ActiveContext context;
    // @vocab applies to predicates/types, not resource @id values: these caches cannot be merged.
    private final Map<String, String> vocabulary = new HashMap<>();
    private final Map<String, String> identifiers = new HashMap<>();

    private NamespaceJsonLdCompactor(ActiveContext context) {
        this.context = context;
    }

    /** Returns null before compaction if any shape needs the general JSON-LD algorithm. */
    static JsonValue compactIfSupported(JsonValue expanded, ActiveContext context) throws JsonLdError {
        if (!namespaceOnly(context) || !(expanded instanceof JsonArray graphs) || graphs.size() != 1
                || !(graphs.getFirst() instanceof JsonObject graph)
                || graph.size() != 2 || !(graph.get("@id") instanceof JsonString)
                || !(graph.get("@graph") instanceof JsonArray nodes)) {
            return null;
        }
        for (var value : nodes) {
            if (!(value instanceof JsonObject node) || !ordinaryNode(node)) {
                return null;
            }
        }
        // All-or-nothing: one @list/@json/RDF-star value sends the whole document to Titanium.
        // Preflight also precedes lazy inverse-context creation, keeping fallback independent.
        var compactor = new NamespaceJsonLdCompactor(context);
        var compactedNodes = JSON.createArrayBuilder();
        for (var node : nodes) {
            compactedNodes.add(compactor.node(node.asJsonObject()));
        }
        return JSON.createObjectBuilder()
                .add("@id", compactor.iri(graph.getString("@id"), false))
                .add("@graph", compactedNodes).build();
    }

    private static boolean namespaceOnly(ActiveContext context) {
        // Containers, coercion and language defaults make term selection depend on the value.
        // Keep this guard strict: our memoization key is only (IRI, vocab), not the full value.
        if (context.getBaseUri() != null || context.getPreviousContext() != null
                || context.getDefaultLanguage() != null || context.getDefaultBaseDirection() != null) {
            return false;
        }
        for (var term : context.getTermsMapping().values()) {
            if (term.getUriMapping() == null || term.getUriMapping().startsWith("@")
                    || !term.getContainerMapping().isEmpty() || term.getTypeMapping() != null
                    || term.getLanguageMapping() != null || term.getDirectionMapping() != null
                    || term.hasLocalContext() || term.isReverseProperty()
                    || term.getIndexMapping() != null || term.getNestValue() != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean ordinaryNode(JsonObject node) {
        if (!(node.get("@id") instanceof JsonString)) {
            return false;
        }
        for (var entry : node.entrySet()) {
            if (entry.getKey().equals("@id")) {
                continue;
            }
            if (!(entry.getValue() instanceof JsonArray values) || values.isEmpty()) {
                return false;
            }
            if (entry.getKey().equals("@type")) {
                if (values.stream().anyMatch(value -> !(value instanceof JsonString))) {
                    return false;
                }
            } else {
                // Expanded keywords carry algorithms, not just names. Let Titanium handle new ones.
                if (entry.getKey().startsWith("@")) {
                    return false;
                }
                for (var value : values) {
                    if (!(value instanceof JsonObject object) || !ordinaryValue(object)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean ordinaryValue(JsonObject value) {
        // String lexical forms stay strings, including "0042"^^xsd:integer; no numeric coercion.
        // Lists, @direction, @json and object-valued RDF-star IDs need general compaction.
        if (value.size() == 1 && value.get("@id") instanceof JsonString) {
            return true;
        }
        if (!(value.get("@value") instanceof JsonString) || value.size() > 2) {
            return false;
        }
        if (value.size() == 1) {
            return true;
        }
        return value.get("@language") instanceof JsonString
                || value.get("@type") instanceof JsonString type && !type.getString().startsWith("@");
    }

    private JsonObject node(JsonObject expanded) throws JsonLdError {
        var result = JSON.createObjectBuilder();
        for (var entry : expanded.entrySet()) {
            String key = entry.getKey();
            if (key.equals("@id")) {
                result.add(key, iri(((JsonString) entry.getValue()).getString(), false));
            } else if (key.equals("@type")) {
                var types = new ArrayList<String>();
                for (var type : entry.getValue().asJsonArray()) {
                    types.add(iri(((JsonString) type).getString(), true));
                }
                // Titanium sorts types even when ordered output is disabled.
                Collections.sort(types);
                if (types.size() == 1) {
                    result.add(key, types.getFirst());
                } else {
                    var array = JSON.createArrayBuilder();
                    types.forEach(array::add);
                    result.add(key, array);
                }
            } else {
                String property = iri(key, true);
                JsonArray values = entry.getValue().asJsonArray();
                // compactArrays=true unwraps property singletons; @graph stays an array above.
                if (values.size() == 1) {
                    result.add(property, value(values.getJsonObject(0)));
                } else {
                    var array = JSON.createArrayBuilder();
                    for (var value : values) {
                        array.add(value(value.asJsonObject()));
                    }
                    result.add(property, array);
                }
            }
        }
        return result.build();
    }

    private JsonValue value(JsonObject expanded) throws JsonLdError {
        if (expanded.containsKey("@id")) {
            return JSON.createObjectBuilder().add("@id", iri(expanded.getString("@id"), false)).build();
        }
        if (expanded.size() == 1) {
            // Only bare @value can become a scalar; retain language/datatype-bearing objects.
            return expanded.get("@value");
        }
        var result = JSON.createObjectBuilder();
        for (var entry : expanded.entrySet()) {
            result.add(entry.getKey(), entry.getKey().equals("@type")
                    ? JSON.createValue(iri(((JsonString) entry.getValue()).getString(), true)) : entry.getValue());
        }
        return result.build();
    }

    private String iri(String expanded, boolean vocab) throws JsonLdError {
        var cache = vocab ? vocabulary : identifiers;
        String compacted = cache.get(expanded);
        if (compacted == null) {
            // Titanium still chooses legal compact IRIs. Memoization reduces its calls from
            // O(term occurrences) to O(distinct IRIs) per conversion; nothing survives the request.
            compacted = context.uriCompaction().vocab(vocab).compact(expanded);
            cache.put(expanded, compacted);
        }
        return compacted;
    }
}
