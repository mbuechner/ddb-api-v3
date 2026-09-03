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
package de.ddb.apiv3.record.persistence;

import de.ddb.apiv3.record.StoredRecordMetadata;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

/** Shared JSON mapping for the identical host-neutral metadata stored by H2 and Cassandra. */
@Component
public final class StoredRecordMetadataJsonCodec {

    private final ObjectReader reader;
    private final ObjectWriter writer;

    public StoredRecordMetadataJsonCodec(ObjectMapper objectMapper) {
        this.reader = objectMapper.readerFor(StoredRecordMetadata.class);
        this.writer = objectMapper.writerFor(StoredRecordMetadata.class);
    }

    /**
     * @param json database representation of the metadata
     * @return decoded domain value
     * @throws DataRetrievalFailureException if stored JSON is invalid
     */
    public StoredRecordMetadata decode(String json) {
        try {
            return reader.readValue(json);
        } catch (JacksonException exception) {
            throw new DataRetrievalFailureException("Stored record metadata is not valid JSON", exception);
        }
    }

    /** Encodes metadata for storage using the same mapping used for reads. */
    public String encode(StoredRecordMetadata metadata) {
        try {
            return writer.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Record metadata cannot be encoded as JSON", exception);
        }
    }
}
