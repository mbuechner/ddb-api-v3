-- Reference schema for a single production datacenter.
-- Adapt the datacenter name and replication factor to the real Cassandra topology
-- before applying this file. The application deliberately uses schema-action=none.
CREATE KEYSPACE IF NOT EXISTS ddb_api_v3
WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'datacenter1': 3
}
AND durable_writes = true;

-- Every API operation addresses exactly one externally assigned record ID, so the
-- ID is the complete partition key. The compare-and-set writes in
-- CassandraRecordRepository use etag as their lightweight-transaction condition.
CREATE TABLE IF NOT EXISTS ddb_api_v3.record_metadata_by_id (
    record_id text PRIMARY KEY,
    metadata_json text,
    rdf_xml blob,
    source_xml blob,
    etag text,
    last_modified timestamp
);
