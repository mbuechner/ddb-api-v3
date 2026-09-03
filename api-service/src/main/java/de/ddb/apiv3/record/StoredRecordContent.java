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
package de.ddb.apiv3.record;

/**
 * Canonical RDF/XML bytes and the metadata needed to serve a content representation.
 *
 * <p>The persistence adapters select this value separately from administrative metadata. Thus a
 * metadata request never transfers a potentially large RDF value from the database.</p>
 *
 * @param metadata stored record metadata
 * @param rdfXml canonical RDF/XML bytes exactly as stored, or {@code null} for a tombstone whose
 *               payload was not retained
 */
public record StoredRecordContent(StoredRecordMetadata metadata, byte[] rdfXml) {
}
