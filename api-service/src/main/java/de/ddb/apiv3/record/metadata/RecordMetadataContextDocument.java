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
 *
 * This code was generated with assistance from OpenAI ChatGPT.
 */
package de.ddb.apiv3.record.metadata;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds the versioned JSON-LD context once so responses can reuse its compact bytes. */
@Component
public final class RecordMetadataContextDocument {

    public static final String FILE_NAME = "record-metadata-v1.jsonld";
    public static final String PATH = "/contexts/" + FILE_NAME;

    private final Resource resource;

    public RecordMetadataContextDocument(ObjectMapper objectMapper) {
        try {
            resource = new ByteArrayResource(objectMapper.writeValueAsBytes(Map.of("@context", context())));
        } catch (JacksonException exception) {
            throw new IllegalStateException("The record metadata JSON-LD context cannot be generated", exception);
        }
    }

    /** Returns the immutable, startup-generated context document. */
    public Resource resource() {
        return resource;
    }

    private static Map<String, Object> context() {
        Map<String, Object> context = new TreeMap<>(Map.ofEntries(
                Map.entry("@version", 1.1),
                Map.entry("dcat", "http://www.w3.org/ns/dcat#"),
                Map.entry("dcterms", "http://purl.org/dc/terms/"),
                Map.entry("foaf", "http://xmlns.com/foaf/0.1/"),
                Map.entry("adms", "http://www.w3.org/ns/adms#"),
                Map.entry("edm", "http://www.europeana.eu/schemas/edm/"),
                Map.entry("skos", "http://www.w3.org/2004/02/skos/core#"),
                Map.entry("prov", "http://www.w3.org/ns/prov#"),
                Map.entry("earl", "http://www.w3.org/ns/earl#"),
                Map.entry("xsd", "http://www.w3.org/2001/XMLSchema#"),
                Map.entry("primaryTopic", Map.of("@id", "foaf:primaryTopic")),
                Map.entry("recordId", "dcterms:identifier"),
                Map.entry("datasets", idSetTerm("dcterms:isPartOf")),
                Map.entry("provider", idTerm("dcterms:publisher")),
                Map.entry("aggregator", idTerm("edm:intermediateProvider")),
                Map.entry("sourceIdentifier", "adms:identifier"),
                Map.entry("notation", "skos:notation"),
                Map.entry("publicationStatus", idTerm("adms:status")),
                Map.entry("invalidatedAtTime", typedTerm("prov:invalidatedAtTime", "xsd:dateTime")),
                Map.entry("validationAssertion", Map.of("@reverse", "earl:subject")),
                Map.entry("test", idTerm("earl:test")),
                Map.entry("mode", idTerm("earl:mode")),
                Map.entry("result", "earl:result"),
                Map.entry("outcome", idTerm("earl:outcome")),
                Map.entry("info", "earl:info"),
                Map.entry("createdAt", typedTerm("dcterms:issued", "xsd:dateTime")),
                Map.entry("modifiedAt", typedTerm("dcterms:modified", "xsd:dateTime")),
                Map.entry("deletionReason", idTerm("dcterms:type")),
                Map.entry("deletionComment", "dcterms:description")));
        return context;
    }

    private static Map<String, String> idTerm(String id) {
        return Map.of("@id", id, "@type", "@id");
    }

    private static Map<String, String> idSetTerm(String id) {
        return Map.of("@id", id, "@type", "@id", "@container", "@set");
    }

    private static Map<String, String> typedTerm(String id, String type) {
        return Map.of("@id", id, "@type", type);
    }
}
