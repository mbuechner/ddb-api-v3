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
package de.ddb.apiv3.records;

import de.ddb.apiv3.config.ApiProperties;
import de.ddb.apiv3.storage.RdfDocumentStore;
import de.ddb.apiv3.rdf.RdfCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.ETag;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

/** Bound the body, let Jena parse it, then store one named graph as Jelly. */
@Service
public final class RecordUploadService {

    private static final String DOCUMENT_NAME = "record";
    private static final String ENTITY_TAG = "(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"";
    private static final Pattern ENTITY_TAG_LIST = Pattern.compile(
            ENTITY_TAG + "(?:[ \\t]*,[ \\t]*" + ENTITY_TAG + ")*");

    private final RdfDocumentStore repository;
    private final RdfCodec rdfCodec;
    private final int maxDocumentBytes;
    private final String recordGraphPrefix;

    public RecordUploadService(
            RdfDocumentStore repository,
            RdfCodec rdfCodec,
            ApiProperties apiProperties,
            @Value("${ddb.api.max-document-size:1MB}") DataSize maxDocumentSize) {
        long bytes = maxDocumentSize.toBytes();
        if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The RDF document size limit must be positive and below 2 GB");
        }
        this.repository = repository;
        this.rdfCodec = rdfCodec;
        this.maxDocumentBytes = (int) bytes;
        this.recordGraphPrefix = apiProperties.baseUrl() + "/records/";
    }

    /** A DDB ID is a namespace: creating its record leaves its item alone. */
    public String create(String recordId, InputStreamSource content, String contentType) {
        byte[] jelly = validatedContent(recordId, content, contentType);
        return repository.create(recordId, DOCUMENT_NAME, jelly)
                .orElseThrow(() -> new RecordAlreadyExistsException(recordId));
    }

    /** Replaces an existing record document only while its S3 ETag still matches. */
    public String update(String recordId, InputStreamSource content, String contentType, String ifMatch) {
        String condition = strongIfMatch(ifMatch);
        byte[] jelly = validatedContent(recordId, content, contentType);
        return repository.replace(recordId, DOCUMENT_NAME, jelly, condition)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PRECONDITION_FAILED, "If-Match does not match an existing record document"));
    }

    private byte[] validatedContent(String recordId, InputStreamSource content, String contentType) {
        byte[] rdf;
        try (InputStream stream = content.getInputStream()) {
            // Limit decompressed input, including requests without a Content-Length header.
            rdf = stream.readNBytes(maxDocumentBytes + 1);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The RDF request body could not be read", exception);
        }
        if (rdf.length > maxDocumentBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The RDF document exceeds the size limit");
        }
        return rdfCodec.toJelly(rdf, contentType, recordGraphPrefix + recordId);
    }

    private static String strongIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        }
        String condition = ifMatch.trim();
        if ("*".equals(condition)) {
            return condition;
        }
        // ETag.parse is forgiving. Write preconditions must consume the entire header.
        if (!ENTITY_TAG_LIST.matcher(condition).matches()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid ETag list");
        }
        try {
            List<ETag> tags = ETag.parse(condition).stream().filter(tag -> !tag.weak()).toList();
            if (!tags.isEmpty()) {
                return tags.stream().map(tag -> tag.formattedTag()).collect(Collectors.joining(", "));
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match is not a valid strong ETag");
        }
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "If-Match requires a strong ETag");
    }
}
