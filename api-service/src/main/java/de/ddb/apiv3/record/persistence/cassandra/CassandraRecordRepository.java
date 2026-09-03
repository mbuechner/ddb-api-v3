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
package de.ddb.apiv3.record.persistence.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.StoredRecordContent;
import de.ddb.apiv3.record.persistence.RecordRepository;
import de.ddb.apiv3.record.persistence.StoredRecordMetadataJsonCodec;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Cassandra adapter for token-aware point lookups by record identifier.
 *
 * <p>The table is query-oriented and uses {@code record_id} as its complete partition key. A
 * lookup therefore reaches one bounded partition and never requires filtering, a secondary index,
 * or a cluster scan. The prepared statement is shared because driver statements are thread-safe.
 * Spring Boot owns exactly one long-lived {@link CqlSession} per application instance; the driver
 * multiplexes requests over its node connections and maintains cluster topology.</p>
 *
 * <p>The synchronous driver call is intentional for the current MVC repository contract. Virtual
 * request threads prevent the wait from consuming platform threads, while driver throttling and
 * request timeouts provide bounded backpressure. A future streaming endpoint should expose an
 * asynchronous repository contract rather than blocking inside this adapter.</p>
 */
@Repository
@Profile("prod")
public class CassandraRecordRepository implements RecordRepository {

    private static final SimpleStatement FIND_BY_ID = SimpleStatement.newInstance("""
            SELECT metadata_json, etag, last_modified
              FROM record_metadata_by_id
             WHERE record_id = ?
            """);
    private static final SimpleStatement FIND_CONTENT_BY_ID = SimpleStatement.newInstance("""
            SELECT metadata_json, rdf_xml
              FROM record_metadata_by_id
             WHERE record_id = ?
            """);
    private static final SimpleStatement CREATE = SimpleStatement.newInstance("""
            INSERT INTO record_metadata_by_id
                (record_id, metadata_json, rdf_xml, source_xml, etag, last_modified)
            VALUES (?, ?, ?, ?, ?, ?)
            IF NOT EXISTS
            """);
    private static final SimpleStatement REPLACE = SimpleStatement.newInstance("""
            UPDATE record_metadata_by_id
               SET metadata_json = ?,
                   rdf_xml = ?,
                   source_xml = ?,
                   etag = ?,
                   last_modified = ?
             WHERE record_id = ?
             IF etag = ?
            """);

    private final CqlSession session;
    private final PreparedStatement findById;
    private final PreparedStatement findContentById;
    private final PreparedStatement create;
    private final PreparedStatement replace;
    private final StoredRecordMetadataJsonCodec metadataCodec;

    public CassandraRecordRepository(CqlSession session, StoredRecordMetadataJsonCodec metadataCodec) {
        this.session = session;
        this.findById = session.prepare(FIND_BY_ID);
        this.findContentById = session.prepare(FIND_CONTENT_BY_ID);
        this.create = session.prepare(CREATE);
        this.replace = session.prepare(REPLACE);
        this.metadataCodec = metadataCodec;
    }

    /**
     * Executes one prepared equality lookup. The driver derives the routing token from the bound
     * partition key and can route directly to a replica.
     *
     * @param recordId validated record identifier and complete partition key
     * @return the record, or an empty value if it is unknown
     */
    @Override
    public Optional<StoredRecord> findById(String recordId) {
        return Optional.ofNullable(session.execute(findById.bind(recordId)).one())
                .map(this::mapRecord);
    }

    @Override
    public Optional<StoredRecordContent> findContentById(String recordId) {
        return Optional.ofNullable(session.execute(findContentById.bind(recordId)).one())
                .map(row -> new StoredRecordContent(
                        metadataCodec.decode(row.getString("metadata_json")),
                        toByteArray(row.getByteBuffer("rdf_xml"))));
    }

    @Override
    public boolean create(StoredRecord record, byte[] rdfXml, byte[] source) {
        BoundStatement statement = create.boundStatementBuilder()
                .setString(0, record.metadata().recordId())
                .setString(1, metadataCodec.encode(record.metadata()))
                .setByteBuffer(2, ByteBuffer.wrap(rdfXml))
                .setString(4, record.etag())
                .setInstant(5, record.lastModified().toInstant())
                .build();
        statement = source == null
                ? statement.setToNull(3)
                : statement.setByteBuffer(3, ByteBuffer.wrap(source));
        Row result = session.execute(statement).one();
        return result != null && result.getBoolean("[applied]");
    }

    @Override
    public boolean replace(StoredRecord record, byte[] rdfXml, byte[] source, String expectedEtag) {
        BoundStatement statement = replace.boundStatementBuilder()
                .setString(0, metadataCodec.encode(record.metadata()))
                .setByteBuffer(1, ByteBuffer.wrap(rdfXml))
                .setString(3, record.etag())
                .setInstant(4, record.lastModified().toInstant())
                .setString(5, record.metadata().recordId())
                .setString(6, expectedEtag)
                .build();
        statement = source == null
                ? statement.setToNull(2)
                : statement.setByteBuffer(2, ByteBuffer.wrap(source));
        Row result = session.execute(statement).one();
        return result != null && result.getBoolean("[applied]");
    }

    private StoredRecord mapRecord(Row row) {
        return new StoredRecord(
                metadataCodec.decode(row.getString("metadata_json")),
                row.getString("etag"),
                OffsetDateTime.ofInstant(row.getInstant("last_modified"), ZoneOffset.UTC));
    }

    private static byte[] toByteArray(ByteBuffer value) {
        // Tombstones may deliberately retain metadata without retaining the RDF payload.
        if (value == null) {
            return null;
        }
        ByteBuffer copy = value.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
