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
package de.ddb.apiv3.record.content;

import de.ddb.apiv3.record.StoredRecordContent;
import de.ddb.apiv3.record.StoredRecordMetadata;
import de.ddb.apiv3.record.persistence.RecordRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.apache.jena.Jena;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves canonical RDF/XML directly and delegates only real format conversions to Jena RIOT.
 *
 * <p>Keeping content retrieval separate from metadata retrieval prevents ordinary metadata calls
 * from loading the database BLOB. The canonical branch deliberately does not initialize a Jena
 * model: clients that request RDF/XML receive the exact stored bytes at the lowest possible cost.</p>
 */
@Service
public final class RecordContentService {

    private static final List<OutputFormat> OUTPUT_FORMATS = List.of(
            new OutputFormat("rdfxml", MediaType.parseMediaType("application/rdf+xml"), null, false),
            new OutputFormat("jsonld", MediaType.parseMediaType("application/ld+json"), RDFFormat.JSONLD, false),
            new OutputFormat("turtle", MediaType.parseMediaType("text/turtle"), RDFFormat.TURTLE_BLOCKS, false),
            new OutputFormat("ntriples", MediaType.parseMediaType("application/n-triples"),
                    RDFFormat.NTRIPLES_UTF8, false),
            new OutputFormat("nquads", MediaType.parseMediaType("application/n-quads"),
                    RDFFormat.NQUADS_UTF8, true),
            new OutputFormat("rdfjson", MediaType.parseMediaType("application/rdf+json"), RDFFormat.RDFJSON, false),
            new OutputFormat("trig", MediaType.parseMediaType("application/trig"), RDFFormat.TRIG_BLOCKS, true),
            new OutputFormat("trix", MediaType.parseMediaType("application/trix"), RDFFormat.TRIX, true),
            new OutputFormat("rdfthrift", MediaType.parseMediaType("application/rdf+thrift"),
                    RDFFormat.RDF_THRIFT, false),
            new OutputFormat("rdfprotobuf", MediaType.parseMediaType("application/rdf+protobuf"),
                    RDFFormat.RDF_PROTO, false));

    private final RecordRepository repository;
    private final URI apiBaseUrl;

    public RecordContentService(
            RecordRepository repository,
            @Value("${ddb.api.public-base-url}") String apiBaseUrl) {
        this.repository = repository;
        this.apiBaseUrl = URI.create(apiBaseUrl);
    }

    /**
     * Resolves one representation without rendering it. The returned body remains lazy so the
     * HTTP adapter can evaluate cache preconditions first.
     *
     * @param recordId stable DDB record identifier
     * @param acceptedMediaTypes values parsed by Spring from the request's {@code Accept} header
     * @return response content and representation metadata
     */
    public Representation getRepresentation(
            String recordId,
            List<MediaType> acceptedMediaTypes) {
        StoredRecordContent stored = repository.findContentById(recordId)
                .orElseThrow(() -> new NoSuchElementException("Record '" + recordId + "' was not found"));
        if (stored.metadata().isTombstone()) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "The record has a tombstone and no current content.");
        }

        OutputFormat outputFormat = negotiate(acceptedMediaTypes);
        String contentSha256 = requireContentSha256(stored.metadata());
        URI recordIri = recordIri(recordId);
        return new Representation(
                outputFormat.isCanonical()
                        ? stored::rdfXml
                        : () -> convert(stored.rdfXml(), outputFormat, recordIri),
                outputFormat.mediaType(),
                etag(stored.metadata(), contentSha256, outputFormat),
                contentSha256,
                stored.metadata().modifiedAt(),
                outputFormat.isCanonical() ? (long) stored.rdfXml().length : null);
    }

    private static OutputFormat negotiate(List<MediaType> acceptedMediaTypes) {
        List<MediaType> requested = acceptedMediaTypes.isEmpty()
                ? List.of(MediaType.ALL)
                : acceptedMediaTypes;
        OutputFormat bestFormat = null;
        double bestQuality = -1;
        int bestSpecificity = -1;

        for (OutputFormat candidate : OUTPUT_FORMATS) {
            double candidateQuality = -1;
            int candidateSpecificity = -1;
            for (MediaType accepted : requested) {
                if (!accepted.isCompatibleWith(candidate.mediaType())) {
                    continue;
                }
                int specificity = specificity(accepted);
                if (specificity > candidateSpecificity
                        || (specificity == candidateSpecificity
                        && accepted.getQualityValue() > candidateQuality)) {
                    candidateQuality = accepted.getQualityValue();
                    candidateSpecificity = specificity;
                }
            }
            if (candidateQuality > 0
                    && (candidateQuality > bestQuality
                    || (candidateQuality == bestQuality && candidateSpecificity > bestSpecificity))) {
                bestFormat = candidate;
                bestQuality = candidateQuality;
                bestSpecificity = candidateSpecificity;
            }
        }

        if (bestFormat == null) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "No supported RDF media type was requested");
        }
        return bestFormat;
    }

    private static int specificity(MediaType mediaType) {
        if (mediaType.isWildcardType()) {
            return 0;
        }
        return mediaType.isWildcardSubtype() ? 1 : 2;
    }

    private static byte[] convert(byte[] rdfXml, OutputFormat outputFormat, URI recordIri) {
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, new ByteArrayInputStream(rdfXml), recordIri.toString(), Lang.RDFXML);
            ByteArrayOutputStream output = new ByteArrayOutputStream(rdfXml.length);
            if (outputFormat.dataset()) {
                Dataset dataset = DatasetFactory.create();
                try {
                    dataset.addNamedModel(recordIri.toString(), model);
                    RDFDataMgr.write(output, dataset, outputFormat.rdfFormat());
                } finally {
                    dataset.close();
                }
            } else {
                RDFDataMgr.write(output, model, outputFormat.rdfFormat());
            }
            return output.toByteArray();
        } catch (RiotException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Stored RDF/XML is invalid", exception);
        } finally {
            model.close();
        }
    }

    private URI recordIri(String recordId) {
        return UriComponentsBuilder.fromUri(apiBaseUrl)
                .pathSegment("3", "records", recordId)
                .build()
                .encode()
                .toUri();
    }

    private static String requireContentSha256(StoredRecordMetadata metadata) {
        if (metadata.sha256() == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Published record content has no SHA-256 metadata");
        }
        return metadata.sha256();
    }

    private static String etag(
            StoredRecordMetadata metadata,
            String contentSha256,
            OutputFormat outputFormat) {
        String converter = outputFormat.isCanonical() ? "stored" : "jena-" + Jena.VERSION;
        return '"' + outputFormat.id() + '-' + converter + '-' + contentSha256 + '"';
    }

    /** Values required by the HTTP adapter without exposing Jena types outside this package. */
    public record Representation(
            Supplier<byte[]> bodyLoader,
            MediaType mediaType,
            String etag,
            String contentSha256,
            java.time.OffsetDateTime lastModified,
            Long contentLength) {
        /** Performs the Jena conversion lazily, after HTTP preconditions have been evaluated. */
        public byte[] body() {
            return bodyLoader.get();
        }
    }

    private record OutputFormat(
            String id,
            MediaType mediaType,
            RDFFormat rdfFormat,
            boolean dataset) {

        boolean isCanonical() {
            return rdfFormat == null;
        }
    }
}
