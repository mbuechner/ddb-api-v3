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

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.compaction.Compaction;
import com.apicatalog.jsonld.context.ActiveContext;
import com.apicatalog.jsonld.processor.ProcessingRuntime;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.nio.charset.StandardCharsets;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.jsonld.JenaToTitanium;
import org.apache.jena.sparql.core.DatasetGraph;

/**
 * Jena owns RDF expansion; namespace compaction has a guarded fast path and a Titanium fallback.
 * No vocabulary-specific assumptions or converted records are retained between requests.
 */
final class JsonLdDocumentWriter {

    private JsonLdDocumentWriter() {
    }

    static byte[] write(DatasetGraph dataset) {
        if (dataset.isEmpty()) {
            // Jena omits empty graphs. Keep the graph identity without processing unused hints.
            return Json.createObjectBuilder()
                    .add("@context", JsonValue.EMPTY_JSON_OBJECT)
                    .add("@id", dataset.listGraphNodes().next().getURI())
                    .add("@graph", JsonValue.EMPTY_JSON_ARRAY).build().toString().getBytes(StandardCharsets.UTF_8);
        }
        var context = Json.createObjectBuilder();
        dataset.prefixes().forEach((prefix, iri) -> {
            // _: identifies blank nodes. A scheme:// authority disambiguates an otherwise recursive hint.
            if (!prefix.equals("_") && (!iri.startsWith(prefix + ":") || iri.startsWith(prefix + "://"))) {
                context.add(prefix.isEmpty() ? "@vocab" : prefix, iri);
            }
        });
        var contextValue = context.build();
        JsonObject document;
        try {
            var options = new JsonLdOptions();
            options.setRdfStar(true);
            // Keep RDF -> JSON-LD in Jena: list recognition and quoted-term semantics still matter.
            var expanded = JenaToTitanium.convert(dataset, options);
            JsonValue compacted;
            try {
                compacted = compact(expanded, contextValue, options);
            } catch (JsonLdError exception) {
                compacted = switch (exception.getCode()) {
                    case CYCLIC_IRI_MAPPING, IRI_CONFUSED_WITH_PREFIX -> {
                        // Hints are optional. Ambiguous schemes use full IRIs, without re-expanding RDF.
                        contextValue = JsonValue.EMPTY_JSON_OBJECT;
                        yield compact(expanded, contextValue, options);
                    }
                    default -> throw exception;
                };
            }
            // One stored named graph produces one compacted object.
            document = compacted.asJsonObject();
        } catch (JsonLdError exception) {
            throw new RiotException("The RDF dataset cannot be compacted as JSON-LD", exception);
        }
        return Json.createObjectBuilder(document).add("@context", contextValue)
                .build().toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonValue compact(JsonValue expanded, JsonObject context, JsonLdOptions options) throws JsonLdError {
        // Titanium validates/resolves namespace hints even when the specialized path will win.
        var active = new ActiveContext(ProcessingRuntime.of(options)).newContext().create(context, null);
        var ordinaryGraph = NamespaceJsonLdCompactor.compactIfSupported(expanded, active);
        if (ordinaryGraph != null) {
            return ordinaryGraph;
        }
        // Unsupported fast-path shape means general compaction, never omitted RDF content.
        return Compaction.with(active).compactArrays(true).compact(expanded);
    }
}
