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

import de.ddb.apiv3.storage.StorageException;
import de.ddb.apiv3.config.RequestTiming;
import de.ddb.apiv3.rdf.validation.RdfValidator;
import eu.neverblink.jelly.convert.jena.JenaConverterFactory;
import eu.neverblink.jelly.convert.jena.riot.JellyFormatVariant;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import eu.neverblink.jelly.core.JellyOptions;
import eu.neverblink.jelly.core.RdfHandler;
import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import eu.neverblink.jelly.core.utils.IoUtils;
import jakarta.json.spi.JsonProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** RDF/XML in, three formats out, Jelly in the bucket. Graphs live for one conversion. */
@Component
public final class RdfCodec {

    public static final String JELLY_CONTENT_TYPE = "application/x-jelly-rdf";
    private static final List<Lang> OUTPUT_LANGUAGES = List.of(
            Lang.JSONLD, Lang.RDFXML, JellyLanguage.JELLY);
    private static final List<MediaType> OUTPUT_MEDIA_TYPES;
    private static final Comparator<MediaType> RANGE_PRECEDENCE = Comparator.comparingInt(RdfCodec::specificity)
            .thenComparingDouble(range -> range.getQualityValue());
    private static final Comparator<MediaType> REPRESENTATION_PREFERENCE =
            Comparator.comparingDouble(MediaType::getQualityValue).thenComparingInt(RdfCodec::specificity);
    private static final RDFFormat STORAGE_FORMAT = new RDFFormat(JellyLanguage.JELLY,
            JellyFormatVariant.builder().options(JellyOptions.SMALL_STRICT)
                    .enableNamespaceDeclarations(true).build());

    static {
        // JSON-P provider discovery per literal is costly inside an executable JAR.
        System.getProperties().putIfAbsent(JsonProvider.JSONP_PROVIDER_FACTORY,
                JsonProvider.provider().getClass().getName());
        JellyLanguage.register();

        // Protocol metadata is immutable; graphs, parsers and writers remain request-local.
        final var offeredTypes = new LinkedHashSet<MediaType>();
        for (final Lang language : OUTPUT_LANGUAGES) {
            offeredTypes.add(MediaType.parseMediaType(contentType(language)));
            language.getAltContentTypes().forEach(type -> offeredTypes.add(MediaType.parseMediaType(type)));
        }
        OUTPUT_MEDIA_TYPES = List.copyOf(offeredTypes);
    }

    /** Validates RDF/XML and stores all its triples under the canonical graph name. */
    public byte[] toJelly(byte[] source, String baseIri) {
        return toJelly(source, baseIri, graph -> { });
    }

    /** Parse once, validate the endpoint's policy on that same graph, then encode it as Jelly. */
    public byte[] toJelly(byte[] source, String baseIri, RdfValidator validator) {
        Graph graph = GraphMemFactory.createDefaultGraph();
        try {
            try (var timing = RequestTiming.start("rdf_parse")) {
                RDFParser.create()
                        .source(new ByteArrayInputStream(source))
                        .lang(Lang.RDFXML)
                        .base(baseIri)
                        .strict(true)
                        .checking(true)
                        .errorHandler(ErrorHandlerFactory.errorHandlerStrictNoLogging)
                        .parse(graph);
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "The request body is not valid RDF/XML", exception);
            }

            // Validation errors must escape as SHACL failures, not be relabeled as RDF/XML syntax errors.
            validator.validate(graph);
            if (graph.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "The RDF document must contain at least one triple");
            }
            try (var timing = RequestTiming.start("jelly_encode")) {
                return encodeGraph(graph, NodeFactory.createURI(baseIri));
            }
        } finally {
            graph.close();
        }
    }

    /**
     * Validates the single named storage graph, then writes the negotiated representation.
     * Dataset formats retain the graph name; RDF/XML exposes its complete triple content.
     * Terms not expressible in the requested format still produce 406, not a lossy conversion.
     */
    public byte[] fromJelly(byte[] jelly, Lang outputLanguage) {
        if (!isOutputLanguage(outputLanguage)) {
            throw notAcceptable();
        }
        // Validation-only sink for Jelly: decode every frame, but skip GraphMem and its triple indexes.
        // The response already is Jelly: validate decoded events, then return the original bytes unchanged.
        DatasetGraph dataset = JellyLanguage.JELLY.equals(outputLanguage)
                ? null : DatasetGraphFactory.createGeneral();
        try {
            try (var timing = RequestTiming.start("jelly_decode")) {
                if (jelly.length == 0) {
                    throw new RiotException("The stored Jelly stream is empty");
                }
                decodeGraphs(jelly, dataset);
            } catch (IOException | RuntimeException exception) {
                throw new StorageException("The stored RDF object is not valid Jelly", exception);
            }

            try (var timing = RequestTiming.start("rdf_serialize")) {
                return switch (contentType(outputLanguage)) {
                    case "application/rdf+xml" -> RdfXmlDocumentWriter.write(graphWithPrefixes(dataset));
                    case "application/ld+json" -> JsonLdDocumentWriter.write(dataset);
                    case JELLY_CONTENT_TYPE -> jelly;
                    default -> throw notAcceptable();
                };
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                        "The RDF object cannot be represented in the requested serialization", exception);
            }
        } finally {
            if (dataset != null) {
                dataset.close();
            }
        }
    }

    /** Negotiates three output formats; JSON-LD wins ties and wildcard requests. */
    public Lang outputLanguage(List<MediaType> accepted) {
        MediaType type = outputMediaType(accepted);
        return RDFLanguages.contentTypeToLang(type.getType() + "/" + type.getSubtype());
    }

    /** Keeps an accepted media-type alias on the response instead of replacing it with its canonical name. */
    public MediaType outputMediaType(List<MediaType> accepted) {
        List<MediaType> ranges = accepted == null || accepted.isEmpty() ? List.of(MediaType.ALL) : accepted;
        MediaType selected = null;
        int selectedRange = -1;
        for (MediaType offered : OUTPUT_MEDIA_TYPES) {
            int match = mostSpecificRange(offered, ranges);
            if (match < 0 || !(ranges.get(match).getQualityValue() > 0)) {
                continue;
            }
            int preference = selectedRange < 0 ? 1
                    : REPRESENTATION_PREFERENCE.compare(ranges.get(match), ranges.get(selectedRange));
            // Equal preferences keep the client's first range, then our offered order (JSON-LD first).
            if (preference > 0 || preference == 0 && match < selectedRange) {
                selected = offered;
                selectedRange = match;
            }
        }
        if (selected == null) {
            throw notAcceptable();
        }
        return ranges.get(selectedRange).getCharset() == null ? selected
                : new MediaType(selected, StandardCharsets.UTF_8);
    }

    private static int mostSpecificRange(MediaType offered, List<MediaType> ranges) {
        int selected = -1;
        for (int index = 0; index < ranges.size(); index++) {
            MediaType range = ranges.get(index);
            if (range.isCompatibleWith(offered) && supportedParameters(range)
                    && (selected < 0 || RANGE_PRECEDENCE.compare(range, ranges.get(selected)) > 0)) {
                selected = index;
            }
        }
        // A specific q=0 must win over an accepting wildcard: reject it only after range selection.
        return selected;
    }

    /** The canonical media type registered by the actual Jena writer. */
    public static String contentType(Lang language) {
        return language.getContentType().getContentTypeStr();
    }

    private static boolean isOutputLanguage(Lang language) {
        return language != null && OUTPUT_LANGUAGES.contains(language);
    }

    private static Graph graphWithPrefixes(DatasetGraph dataset) {
        Graph graph = dataset.getGraph(dataset.listGraphNodes().next());
        graph.getPrefixMapping().setNsPrefixes(dataset.prefixes().getMappingCopy());
        return graph;
    }

    private static void addPrefix(DatasetGraph dataset, String prefix, String iri) {
        // JSON-LD keywords are not namespace names and must never become context directives.
        if (prefix.startsWith("@")) {
            throw new RiotException("A namespace prefix must not start with @");
        }
        // A validation-only sink still checks the prefix, but need not retain namespace mappings.
        if (dataset != null) {
            dataset.prefixes().add(prefix, iri);
        }
    }

    // RIOT owns Jelly 1.2 framing and encoding; every quad uses the canonical graph name.
    private static byte[] encodeGraph(Graph graph, Node graphName) {
        var output = new ByteArrayOutputStream();
        var writer = StreamRDFWriter.getWriterStream(output, STORAGE_FORMAT);
        writer.start();
        graph.getPrefixMapping().getNsPrefixMap().forEach(writer::prefix);
        graph.find().forEachRemaining(triple -> writer.quad(Quad.create(graphName, triple)));
        writer.finish();
        return output.toByteArray();
    }

    /**
     * A null destination is a validation-only sink. Decoder/framing, prefix and single-graph checks
     * run in both modes; only graph allocation and triple retention are conditional.
     */
    private static void decodeGraphs(byte[] jelly, DatasetGraph dataset) throws IOException {
        final class StoredGraphHandler implements RdfHandler.QuadHandler<Node> {
            private Graph currentGraph;
            private Node graphName;

            @Override
            public void handleNamespace(String prefix, Node iri) {
                addPrefix(dataset, prefix, iri.getURI());
            }

            @Override
            public void handleQuad(Node subject, Node predicate, Node object, Node graph) {
                if (graphName == null) {
                    if (graph == null || !graph.isURI() || Quad.isDefaultGraph(graph)) {
                        throw new RiotException("Stored Jelly must contain exactly one named graph");
                    }
                    graphName = graph;
                    if (dataset != null) {
                        currentGraph = GraphMemFactory.createDefaultGraph();
                        dataset.addGraph(graph, currentGraph);
                    }
                } else if (!graphName.equals(graph)) {
                    throw new RiotException("Stored Jelly must contain exactly one named graph");
                }
                if (currentGraph != null) {
                    currentGraph.add(Triple.create(subject, predicate, object));
                }
            }
        }
        var handler = new StoredGraphHandler();
        var decoder = JenaConverterFactory.getInstance().quadsDecoder(handler, JellyOptions.DEFAULT_SUPPORTED_OPTIONS);
        // Consume all frames even for Jelly passthrough: corruption may hide after a valid first frame.
        IoUtils.readStream(new ByteArrayInputStream(jelly), RdfStreamFrame.getFactory(), decoder::ingestFrame);
        if (handler.graphName == null) {
            throw new RiotException("The stored Jelly graph is empty");
        }
    }

    private static int specificity(MediaType type) {
        int wildcardRank = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
        int result = wildcardRank * 100;
        for (String parameter : type.getParameters().keySet()) {
            if (!parameter.equalsIgnoreCase("q")) {
                result++;
            }
        }
        return result;
    }

    private static boolean supportedParameters(MediaType range) {
        for (String parameter : range.getParameters().keySet()) {
            if (!parameter.equalsIgnoreCase("q")
                    && !(parameter.equalsIgnoreCase("charset") && StandardCharsets.UTF_8.equals(range.getCharset()))) {
                return false;
            }
        }
        return true;
    }

    private static ResponseStatusException notAcceptable() {
        return new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                "Accept must allow JSON-LD, RDF/XML or Jelly-RDF");
    }
}
