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
package de.ddb.apiv3;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.StoredRecordMetadata;
import de.ddb.apiv3.record.persistence.RecordRepository;
import de.ddb.apiv3.support.CassandraTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CQL, JSON mapping and conditional writes against a real Cassandra node.
 *
 * <p>The container loads the packaged production reference schema, while {@code LOCAL_ONE} keeps
 * a single-node test usable although that schema models three replicas. This suite cannot validate
 * replication, {@code LOCAL_QUORUM}, repair, rebalancing or failover; those remain deployment-level
 * concerns. It is skipped when Docker is unavailable.</p>
 */
@SpringBootTest(properties = {
    "ddb.api.public-base-url=http://localhost:8080",
    "ddb.resource-base-url=https://www.deutsche-digitale-bibliothek.de",
    // Concrete fallback values let the prod profile bind before Testcontainers replaces
    // the connection through @ServiceConnection.
    "spring.cassandra.contact-points=127.0.0.1:9042",
    "spring.cassandra.local-datacenter=datacenter1",
    "spring.cassandra.username=cassandra",
    "spring.cassandra.password=cassandra",
    "spring.cassandra.keyspace-name=ddb_api_v3",
    "spring.cassandra.request.timeout=5s",
    "spring.cassandra.request.consistency=LOCAL_ONE",
    "spring.cassandra.schema-action=none"
})
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class CassandraRecordRepositoryIntegrationTest {

    private static final String RECORD_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String CREATED_ID = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
    private static final String TOMBSTONE_ID = "TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT";
    private static final OffsetDateTime LAST_MODIFIED =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final byte[] RDF_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="https://example.org/record/demo-001">
                <rdf:type rdf:resource="http://www.europeana.eu/schemas/edm/ProvidedCHO"/>
              </rdf:Description>
            </rdf:RDF>
            """.getBytes(StandardCharsets.UTF_8);

    @Container
    @ServiceConnection
    static final CassandraContainer CASSANDRA = CassandraTestContainer.create();

    @Autowired
    private CqlSession session;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordRepository repository;

    @BeforeEach
    void seedRecord() throws Exception {
        // A clean table makes the LWT assertions independent of test execution order.
        session.execute("TRUNCATE record_metadata_by_id");
        StoredRecordMetadata metadata = metadata(RECORD_ID);

        session.execute(SimpleStatement.newInstance("""
                        INSERT INTO record_metadata_by_id
                            (record_id, metadata_json, rdf_xml, etag, last_modified)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                RECORD_ID,
                objectMapper.writeValueAsString(metadata),
                ByteBuffer.wrap(RDF_XML),
                "\"record-demo-001-v1\"",
                LAST_MODIFIED.toInstant()));
    }

    private static StoredRecordMetadata metadata(String recordId) {
        return new StoredRecordMetadata(
                recordId,
                List.of("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                null,
                null,
                LAST_MODIFIED,
                LAST_MODIFIED,
                null,
                null,
                null,
                StoredRecordMetadata.PublicationStatus.PUBLISHED,
                "ddb-v3",
                "application/rdf+xml",
                (long) RDF_XML.length,
                "4210365e81dbc38157df7d95b6a819f311fbd205b5b65421e244b77d84a693fe",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                StoredRecordMetadata.ValidationStatus.VALID);
    }

    @Test
    void readsStoredMetadataWithCacheValidators() {
        assertThat(repository.findById(RECORD_ID))
                .hasValueSatisfying(record -> assertThat(record)
                        .extracting(
                                stored -> stored.metadata().recordId(),
                                StoredRecord::etag,
                                StoredRecord::lastModified)
                        .containsExactly(RECORD_ID, "\"record-demo-001-v1\"", LAST_MODIFIED));
    }

    @Test
    void returnsEmptyForUnknownPartitionKey() {
        assertThat(repository.findById("22222222222222222222222222222222")).isEmpty();
    }

    @Test
    void readsCanonicalRdfXmlWithContentMetadata() {
        assertThat(repository.findContentById(RECORD_ID))
                .hasValueSatisfying(content -> {
                    assertThat(content.rdfXml()).containsExactly(RDF_XML);
                    assertThat(content.metadata().recordId()).isEqualTo(RECORD_ID);
                });
    }

    @Test
    void readsATombstoneWithoutRetainedRdfBytes() throws Exception {
        StoredRecordMetadata tombstone = tombstone(TOMBSTONE_ID);
        session.execute(SimpleStatement.newInstance("""
                        INSERT INTO record_metadata_by_id
                            (record_id, metadata_json, rdf_xml, etag, last_modified)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                TOMBSTONE_ID,
                objectMapper.writeValueAsString(tombstone),
                null,
                "\"tombstone\"",
                LAST_MODIFIED.toInstant()));

        assertThat(repository.findContentById(TOMBSTONE_ID))
                .get()
                .satisfies(content -> {
                    assertThat(content.metadata().isTombstone()).isTrue();
                    assertThat(content.rdfXml()).isNull();
                });
    }

    @Test
    void createsAnUnknownIdButNeverOverwritesIt() {
        byte[] source = "retained source".getBytes(StandardCharsets.UTF_8);
        StoredRecord record = new StoredRecord(
                metadata(CREATED_ID), "\"created\"", LAST_MODIFIED.plusSeconds(1));

        assertThat(repository.create(record, RDF_XML, source)).isTrue();
        assertThat(repository.create(
                        new StoredRecord(metadata(CREATED_ID), "\"duplicate\"", LAST_MODIFIED.plusSeconds(2)),
                        "other".getBytes(StandardCharsets.UTF_8),
                        null))
                .isFalse();

        assertThat(repository.findById(CREATED_ID))
                .get()
                .extracting(StoredRecord::etag)
                .isEqualTo("\"created\"");
        assertThat(readSource(CREATED_ID)).containsExactly(source);
    }

    @Test
    void replacesOnlyWhenTheStoredEtagStillMatches() {
        byte[] replacement = "replacement RDF bytes".getBytes(StandardCharsets.UTF_8);
        StoredRecord record = new StoredRecord(
                metadata(RECORD_ID), "\"record-demo-001-v2\"", LAST_MODIFIED.plusSeconds(1));

        assertThat(repository.replace(record, replacement, null, "\"stale\"")).isFalse();
        assertThat(repository.findById(RECORD_ID)).get().extracting(StoredRecord::etag)
                .isEqualTo("\"record-demo-001-v1\"");

        assertThat(repository.replace(record, replacement, null, "\"record-demo-001-v1\""))
                .isTrue();
        assertThat(repository.findContentById(RECORD_ID))
                .get()
                .satisfies(content -> assertThat(content.rdfXml()).containsExactly(replacement));
        assertThat(readSource(RECORD_ID)).isNull();
    }

    private byte[] readSource(String recordId) {
        Row row = session.execute(SimpleStatement.newInstance(
                        "SELECT source_xml FROM record_metadata_by_id WHERE record_id = ?", recordId))
                .one();
        assertThat(row).isNotNull();
        ByteBuffer source = row.getByteBuffer("source_xml");
        if (source == null) {
            return null;
        }
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    private static StoredRecordMetadata tombstone(String recordId) {
        StoredRecordMetadata published = metadata(recordId);
        return new StoredRecordMetadata(
                published.recordId(),
                published.datasetIds(),
                published.providerId(),
                published.aggregatorId(),
                published.sourceId(),
                published.createdAt(),
                LAST_MODIFIED,
                LAST_MODIFIED,
                java.net.URI.create("https://example.org/deletion-reasons/provider-request"),
                "Removed by the provider.",
                StoredRecordMetadata.PublicationStatus.DELETED,
                published.profileVersion(),
                null,
                null,
                null,
                published.sourceMediaType(),
                published.sourceFormat(),
                published.sourceFormatUri(),
                published.sourceFormatVersion(),
                published.sourceProfile(),
                published.sourceProfileUri(),
                published.sourceLength(),
                published.sourceSha256(),
                published.validationStatus());
    }
}
