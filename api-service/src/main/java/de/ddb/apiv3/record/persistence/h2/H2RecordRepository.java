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
package de.ddb.apiv3.record.persistence.h2;

import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.StoredRecordContent;
import de.ddb.apiv3.record.persistence.RecordRepository;
import de.ddb.apiv3.record.persistence.StoredRecordMetadataJsonCodec;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Local adapter using Spring's {@link JdbcClient} and the auto-configured embedded H2 database. */
@Repository
@Profile("dev")
public class H2RecordRepository implements RecordRepository {

    private static final String FIND_BY_ID = """
            SELECT metadata_json, etag, last_modified
              FROM record_metadata_by_id
             WHERE record_id = :recordId
            """;
    private static final String FIND_CONTENT_BY_ID = """
            SELECT metadata_json, rdf_xml
              FROM record_metadata_by_id
             WHERE record_id = :recordId
            """;
    private static final String CREATE = """
            INSERT INTO record_metadata_by_id
                (record_id, metadata_json, rdf_xml, source_xml, etag, last_modified)
            VALUES
                (:recordId, :metadataJson, :rdfXml, :sourceXml, :etag, :lastModified)
            """;
    private static final String REPLACE = """
            UPDATE record_metadata_by_id
               SET metadata_json = :metadataJson,
                   rdf_xml = :rdfXml,
                   source_xml = :sourceXml,
                   etag = :etag,
                   last_modified = :lastModified
             WHERE record_id = :recordId
               AND etag = :expectedEtag
            """;

    private final JdbcClient jdbcClient;
    private final StoredRecordMetadataJsonCodec metadataCodec;

    public H2RecordRepository(JdbcClient jdbcClient, StoredRecordMetadataJsonCodec metadataCodec) {
        this.jdbcClient = jdbcClient;
        this.metadataCodec = metadataCodec;
    }

    @Override
    public Optional<StoredRecord> findById(String recordId) {
        return jdbcClient
                .sql(FIND_BY_ID)
                .param("recordId", recordId)
                .query((resultSet, rowNumber) -> new StoredRecord(
                        metadataCodec.decode(resultSet.getString("metadata_json")),
                        resultSet.getString("etag"),
                        resultSet.getObject("last_modified", OffsetDateTime.class)))
                .optional();
    }

    @Override
    public Optional<StoredRecordContent> findContentById(String recordId) {
        return jdbcClient
                .sql(FIND_CONTENT_BY_ID)
                .param("recordId", recordId)
                .query((resultSet, rowNumber) -> new StoredRecordContent(
                        metadataCodec.decode(resultSet.getString("metadata_json")),
                        resultSet.getBytes("rdf_xml")))
                .optional();
    }

    @Override
    public boolean create(StoredRecord record, byte[] rdfXml, byte[] source) {
        try {
            return jdbcClient.sql(CREATE)
                    .param("recordId", record.metadata().recordId())
                    .param("metadataJson", metadataCodec.encode(record.metadata()))
                    .param("rdfXml", rdfXml)
                    .param("sourceXml", source)
                    .param("etag", record.etag())
                    .param("lastModified", record.lastModified())
                    .update() == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean replace(StoredRecord record, byte[] rdfXml, byte[] source, String expectedEtag) {
        return jdbcClient.sql(REPLACE)
                .param("metadataJson", metadataCodec.encode(record.metadata()))
                .param("rdfXml", rdfXml)
                .param("sourceXml", source)
                .param("etag", record.etag())
                .param("lastModified", record.lastModified())
                .param("recordId", record.metadata().recordId())
                .param("expectedEtag", expectedEtag)
                .update() == 1;
    }
}
