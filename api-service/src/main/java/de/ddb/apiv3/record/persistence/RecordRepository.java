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

import de.ddb.apiv3.record.StoredRecord;
import de.ddb.apiv3.record.StoredRecordContent;

import java.util.Optional;

/**
 * Technology-neutral persistence boundary for records.
 *
 * <p>Implementations must be thread-safe. Production adapters must not retain request or record
 * state in the application process; this lets orchestrators add and remove API instances without
 * sticky sessions, distributed application locks, or cache coordination. Development and test
 * adapters may keep deterministic, disposable fixture data in memory.</p>
 */
public interface RecordRepository {

    /**
     * Looks up a record by its stable DDB identifier.
     *
     * @param recordId validated record identifier
     * @return the record, or an empty value when it does not exist
     */
    Optional<StoredRecord> findById(String recordId);

    /**
     * Reads the canonical RDF/XML independently from the administrative metadata lookup.
     *
     * @param recordId validated record identifier
     * @return content and the metadata needed for lifecycle and cache handling
     */
    Optional<StoredRecordContent> findContentById(String recordId);

    /**
     * Creates a record only if its stable identifier is not present yet.
     *
     * @param record metadata and HTTP validators
     * @param rdfXml validated canonical RDF/XML
     * @param source optional retained source bytes
     * @return {@code true} when inserted, {@code false} when the identifier already exists
     */
    boolean create(StoredRecord record, byte[] rdfXml, byte[] source);

    /**
     * Atomically replaces all stored data only while the current strong ETag still matches.
     *
     * @param record replacement state and new HTTP validator
     * @param rdfXml validated replacement RDF/XML
     * @param source optional replacement source bytes; {@code null} removes an existing source
     * @param expectedEtag strong ETag observed by the client and checked by the repository
     * @return {@code true} when replaced, {@code false} after a concurrent change or deletion
     */
    boolean replace(StoredRecord record, byte[] rdfXml, byte[] source, String expectedEtag);
}
