CREATE TABLE IF NOT EXISTS record_metadata_by_id (
    record_id VARCHAR(32) PRIMARY KEY,
    metadata_json CLOB NOT NULL,
    rdf_xml BLOB NOT NULL,
    source_xml BLOB,
    etag VARCHAR(255) NOT NULL,
    last_modified TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE record_metadata_by_id ADD COLUMN IF NOT EXISTS source_xml BLOB;
