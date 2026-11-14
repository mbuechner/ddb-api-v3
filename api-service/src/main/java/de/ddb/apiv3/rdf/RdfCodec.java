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
import eu.neverblink.jelly.convert.jena.JenaConverterFactory;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import eu.neverblink.jelly.core.JellyOptions;
import eu.neverblink.jelly.core.ProtoEncoder;
import eu.neverblink.jelly.core.RdfHandler;
import eu.neverblink.jelly.core.memory.RowBuffer;
import eu.neverblink.jelly.core.proto.v1.LogicalStreamType;
import eu.neverblink.jelly.core.proto.v1.PhysicalStreamType;
import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import eu.neverblink.jelly.core.utils.IoUtils;
import jakarta.json.spi.JsonProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandlerFactory;
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
    private static final Comparator<MediaType> PRECEDENCE = Comparator.comparingInt(RdfCodec::specificity)
            .thenComparingDouble(range -> range.getQualityValue());

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
        Graph graph = GraphMemFactory.createDefaultGraph();
        try {
            try {
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

            // TODO: SHACL ergänzen.
            return encodeGraph(graph, NodeFactory.createURI(baseIri));
        } finally {
            graph.close();
        }
    }

    /** Dataset formats retain the graph name; graph formats expose its complete triple content. */
    public byte[] fromJelly(byte[] jelly, Lang outputLanguage) {
        if (!isOutputLanguage(outputLanguage)) {
            throw notAcceptable();
        }
        DatasetGraph dataset = DatasetGraphFactory.createGeneral();
        try {
            try {
                if (jelly.length == 0) {
                    throw new RiotException("The stored Jelly stream is empty");
                }
                decodeGraphs(jelly, dataset);
            } catch (IOException | RuntimeException exception) {
                throw new StorageException("The stored RDF object is not valid Jelly", exception);
            }

            try {
                return switch (contentType(outputLanguage)) {
                    case "application/rdf+xml" -> RdfXmlRecordWriter.write(graphWithPrefixes(dataset));
                    case "application/ld+json" -> JsonLdRecordWriter.write(dataset);
                    case JELLY_CONTENT_TYPE -> jelly;
                    default -> throw notAcceptable();
                };
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                        "The RDF object cannot be represented in the requested serialization", exception);
            }
        } finally {
            dataset.close();
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
        Comparator<MediaType> preference = Comparator.comparingDouble((MediaType range) -> range.getQualityValue())
                .thenComparingInt(RdfCodec::specificity)
                .thenComparingInt(range -> -ranges.indexOf(range));
        return OUTPUT_MEDIA_TYPES.stream()
                .flatMap(offered -> ranges.stream()
                        .filter(range -> range.isCompatibleWith(offered) && supportedParameters(range))
                        .max(PRECEDENCE)
                        .filter(range -> range.getQualityValue() > 0)
                        .map(range -> Map.entry(offered, range)).stream())
                .max(Map.Entry.comparingByValue(preference))
                .map(match -> match.getValue().getCharset() == null ? match.getKey()
                        : new MediaType(match.getKey(), StandardCharsets.UTF_8))
                .orElseThrow(RdfCodec::notAcceptable);
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
        dataset.prefixes().add(prefix, iri);
    }

    // RIOT's Jelly writer uses flat quads and loses empty graphs; keep explicit boundaries.
    private static byte[] encodeGraph(Graph graph, Node graphName) {
        RowBuffer rows = RowBuffer.newLazyImmutable();
        var options = JellyOptions.SMALL_ALL_FEATURES.clone()
                .setPhysicalType(PhysicalStreamType.GRAPHS)
                .setLogicalType(LogicalStreamType.NAMED_GRAPHS);
        var encoder = JenaConverterFactory.getInstance().encoder(ProtoEncoder.Params.of(options, true, rows));
        graph.getPrefixMapping().getNsPrefixMap()
                .forEach((prefix, iri) -> encoder.handleNamespace(prefix, NodeFactory.createURI(iri)));
        encoder.handleGraphStart(graphName);
        graph.find().forEachRemaining(triple ->
                encoder.handleTriple(triple.getSubject(), triple.getPredicate(), triple.getObject()));
        encoder.handleGraphEnd();
        return RdfStreamFrame.newInstance().setRows(rows).toByteArrayDelimited();
    }

    private static void decodeGraphs(byte[] jelly, DatasetGraph dataset) throws IOException {
        final class StoredGraphHandler implements RdfHandler.GraphHandler<Node> {
            private Graph currentGraph;
            private int graphCount;

            @Override
            public void handleNamespace(String prefix, Node iri) {
                addPrefix(dataset, prefix, iri.getURI());
            }

            @Override
            public void handleGraphStart(Node graph) {
                if (currentGraph != null || graphCount != 0 || graph == null || !graph.isURI()
                        || Quad.isDefaultGraph(graph)) {
                    throw new RiotException("Stored Jelly must contain exactly one named graph");
                }
                graphCount++;
                currentGraph = GraphMemFactory.createDefaultGraph();
                dataset.addGraph(graph, currentGraph);
            }

            @Override
            public void handleTriple(Node subject, Node predicate, Node object) {
                if (currentGraph == null) {
                    throw new RiotException("A stored Jelly triple has no graph");
                }
                currentGraph.add(Triple.create(subject, predicate, object));
            }

            @Override
            public void handleGraphEnd() {
                if (currentGraph == null) {
                    throw new RiotException("A stored Jelly graph ends without a start");
                }
                currentGraph = null;
            }
        }
        var handler = new StoredGraphHandler();
        var decoder = JenaConverterFactory.getInstance().graphsDecoder(handler, JellyOptions.DEFAULT_SUPPORTED_OPTIONS);
        IoUtils.readStream(new ByteArrayInputStream(jelly), RdfStreamFrame.getFactory(),
                frame -> frame.getRows().forEach(decoder::ingestRow));
        if (handler.currentGraph != null || handler.graphCount != 1) {
            throw new RiotException("The stored Jelly graph is incomplete");
        }
    }

    private static int specificity(MediaType type) {
        int wildcardRank = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
        return wildcardRank * 100 + (int) type.getParameters().keySet().stream()
                .filter(parameter -> !parameter.equalsIgnoreCase("q")).count();
    }

    private static boolean supportedParameters(MediaType range) {
        return range.getParameters().keySet().stream().allMatch(parameter -> parameter.equalsIgnoreCase("q")
                || parameter.equalsIgnoreCase("charset") && StandardCharsets.UTF_8.equals(range.getCharset()));
    }

    private static ResponseStatusException notAcceptable() {
        return new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                "Accept must allow JSON-LD, RDF/XML or Jelly-RDF");
    }
}
