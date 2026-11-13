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
 */
package de.ddb.apiv3.rdf;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RDFWriterRegistry;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.riot.system.jsonld.TitaniumJsonLdOptions;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** RIOT at the edges, Jelly in the bucket. Jena objects live only for one conversion. */
@Component
public final class RdfCodec {

    public static final String JELLY_CONTENT_TYPE = "application/x-jelly-rdf";

    public RdfCodec() {
        JellyLanguage.register();
    }

    /** Parses the complete upload and merges its triples into the requested canonical graph. */
    public byte[] toJelly(byte[] source, String contentType, String baseIri) {
        Lang language = inputLanguage(contentType);
        DatasetGraph dataset = DatasetGraphFactory.createGeneral();
        Node graphName = NodeFactory.createURI(baseIri);
        dataset.addGraph(graphName, GraphMemFactory.createDefaultGraph());
        try {
            try {
                RDFParser.create()
                        .source(new ByteArrayInputStream(source))
                        .lang(language)
                        .base(baseIri)
                        .strict(true)
                        .checking(true)
                        // JSON-LD is self-contained; resolving identifiers never fetches documents.
                        .set(TitaniumJsonLdOptions.JSONLD_OPTIONS, localJsonLdOptions())
                        .errorHandler(ErrorHandlerFactory.errorHandlerStrictNoLogging)
                        .parse(new StreamRDFBase() {
                            @Override
                            public void triple(Triple triple) {
                                dataset.add(Quad.create(graphName, triple));
                            }

                            @Override
                            public void quad(Quad quad) {
                                triple(quad.asTriple());
                            }

                            @Override
                            public void prefix(String prefix, String iri) {
                                dataset.prefixes().add(prefix, iri);
                            }
                        });
            } catch (RuntimeException exception) {
                throw new InvalidRdfException(exception);
            }

            // TODO: SHACL ergänzen.
            return encodeGraph(dataset, graphName);
        } finally {
            dataset.close();
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

            if (outputLanguage.equals(JellyLanguage.JELLY)) {
                return jelly.clone();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                if (outputLanguage.equals(Lang.RDFJSON) && dataset.stream().anyMatch(quad ->
                        quad.getSubject().isTripleTerm() || quad.getObject().isTripleTerm())) {
                    // Jena's legacy RDF/JSON writer silently drops triple terms instead of failing.
                    throw new RiotException("RDF/JSON cannot represent RDF triple terms");
                }
                if (RDFWriterRegistry.getWriterDatasetFactory(outputLanguage) != null) {
                    RDFWriter.create().source(dataset).lang(outputLanguage).output(output);
                } else {
                    RDFWriter.create().source(dataset.getUnionGraph()).lang(outputLanguage).output(output);
                }
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                        "The RDF object cannot be represented in the requested serialization", exception);
            }
            return output.toByteArray();
        } finally {
            dataset.close();
        }
    }

    /** Negotiates registered RDF writers, including quality factors and explicit q=0 exclusions. */
    public Lang outputLanguage(List<MediaType> accepted) {
        MediaType type = outputMediaType(accepted);
        return RDFLanguages.contentTypeToLang(type.getType() + "/" + type.getSubtype());
    }

    /** Keeps an accepted media-type alias on the response instead of replacing it with its canonical name. */
    public MediaType outputMediaType(List<MediaType> accepted) {
        List<MediaType> ranges = accepted == null || accepted.isEmpty() ? List.of(MediaType.ALL) : accepted;
        List<Lang> languages = new ArrayList<>(RDFWriterRegistry.registeredLangs());
        languages.removeIf(language -> !isOutputLanguage(language));
        languages.sort(Comparator.comparing(language -> language.equals(Lang.TRIG) ? "" : contentType(language)));
        var offeredTypes = new LinkedHashSet<MediaType>();
        for (Lang language : languages) {
            offeredTypes.add(MediaType.parseMediaType(contentType(language)));
            language.getAltContentTypes().forEach(type -> offeredTypes.add(MediaType.parseMediaType(type)));
        }

        MediaType selected = null;
        double quality = 0;
        int specificity = -1;
        int order = Integer.MAX_VALUE;
        for (MediaType offered : offeredTypes) {
            MediaType bestRange = null;
            int bestSpecificity = -1;
            int bestOrder = Integer.MAX_VALUE;
            for (int index = 0; index < ranges.size(); index++) {
                MediaType range = ranges.get(index);
                int rank = specificity(range);
                if (range.isCompatibleWith(offered) && supportedParameters(range) && (rank > bestSpecificity
                        || rank == bestSpecificity && bestRange != null
                                && range.getQualityValue() > bestRange.getQualityValue())) {
                    bestRange = range;
                    bestSpecificity = rank;
                    bestOrder = index;
                }
            }
            if (bestRange != null && bestRange.getQualityValue() > 0
                    && (bestRange.getQualityValue() > quality
                            || bestRange.getQualityValue() == quality && bestSpecificity > specificity
                            || bestRange.getQualityValue() == quality && bestSpecificity == specificity
                                    && bestOrder < order)) {
                selected = bestRange.getCharset() == null ? offered : new MediaType(offered, StandardCharsets.UTF_8);
                quality = bestRange.getQualityValue();
                specificity = bestSpecificity;
                order = bestOrder;
            }
        }
        if (selected == null) {
            throw notAcceptable();
        }
        return selected;
    }

    /** The canonical media type registered by the actual Jena writer. */
    public static String contentType(Lang language) {
        return language.getContentType().getContentTypeStr();
    }

    private static Lang inputLanguage(String contentType) {
        try {
            MediaType type = MediaType.parseMediaType(contentType == null ? "" : contentType);
            Lang language = RDFLanguages.contentTypeToLang(type.getType() + "/" + type.getSubtype());
            if (!type.isWildcardType() && !type.isWildcardSubtype() && language != null
                    && RDFLanguages.hasRegisteredParser(language) && isRdfLanguage(language)) {
                return language;
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must identify a registered Jena RDF parser", exception);
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type must identify a registered Jena RDF parser");
    }

    private static boolean isOutputLanguage(Lang language) {
        return language != null && language.getContentType() != null && isRdfLanguage(language)
                && (RDFWriterRegistry.getWriterGraphFactory(language) != null
                        || RDFWriterRegistry.getWriterDatasetFactory(language) != null);
    }

    private static boolean isRdfLanguage(Lang language) {
        // RIOT's debug dumps and /dev/null writer are not RDF serializations.
        return !language.equals(Lang.RDFNULL) && !language.equals(Lang.RDFRAW);
    }

    private static JsonLdOptions localJsonLdOptions() {
        return new JsonLdOptions((uri, options) -> {
            throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                    "External JSON-LD contexts are disabled; include the context in the request body");
        });
    }

    private static byte[] encodeGraph(DatasetGraph dataset, Node graphName) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RowBuffer rows = RowBuffer.newReusableForEncoder(256);
        var options = JellyOptions.SMALL_ALL_FEATURES.clone()
                .setPhysicalType(PhysicalStreamType.GRAPHS)
                .setLogicalType(LogicalStreamType.NAMED_GRAPHS);
        var encoder = JenaConverterFactory.getInstance().encoder(ProtoEncoder.Params.of(options, false, rows));
        encoder.handleGraphStart(graphName);
        dataset.getGraph(graphName).find().forEachRemaining(triple -> {
            encoder.handleTriple(triple.getSubject(), triple.getPredicate(), triple.getObject());
            if (rows.size() >= 256) {
                flushFrame(rows, output);
            }
        });
        encoder.handleGraphEnd();
        flushFrame(rows, output);
        return output.toByteArray();
    }

    private static void flushFrame(RowBuffer rows, ByteArrayOutputStream output) {
        // Explicit graph boundaries survive even an empty upload. No outer compression, ever.
        output.writeBytes(RdfStreamFrame.newInstance().setRows(rows).toByteArrayDelimited());
        rows.clear();
    }

    private static void decodeGraphs(byte[] jelly, DatasetGraph dataset) throws IOException {
        class StoredGraphHandler implements RdfHandler.GraphHandler<Node> {
            private Node currentGraph;
            private int graphCount;

            @Override
            public void handleGraphStart(Node graph) {
                if (currentGraph != null || graphCount != 0 || graph == null || !graph.isURI()
                        || Quad.isDefaultGraph(graph)) {
                    throw new RiotException("Stored Jelly must contain exactly one named graph");
                }
                graphCount++;
                currentGraph = graph;
                dataset.addGraph(graph, GraphMemFactory.createDefaultGraph());
            }

            @Override
            public void handleTriple(Node subject, Node predicate, Node object) {
                if (currentGraph == null) {
                    throw new RiotException("A stored Jelly triple has no graph");
                }
                dataset.add(currentGraph, subject, predicate, object);
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
                "Accept must allow a registered Jena RDF serialization");
    }
}
