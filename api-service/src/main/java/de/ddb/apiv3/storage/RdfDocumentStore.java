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
package de.ddb.apiv3.storage;

import java.util.Optional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Stores uncompressed Jelly documents by DDB identifier and the name chosen by an API method.
 *
 * <p>The document itself is the stored object. Implementations must apply write conditions
 * atomically and must not keep document state or locks in an application instance.</p>
 */
public interface RdfDocumentStore {

    /**
     * Creates one named RDF document only if its object does not already exist.
     *
     * @param recordId 32-character DDB identifier
     * @param documentName object name chosen by the API method, for example {@code record}
     * @param jelly validated, uncompressed Jelly bytes to retain unchanged
     * @return the storage ETag, or empty when a concurrent or previous create already won
     */
    Optional<String> create(String recordId, String documentName, byte[] jelly);

    /**
     * Replaces one named RDF document only while its current storage ETag still matches.
     *
     * @param recordId 32-character DDB identifier
     * @param documentName object name chosen by the API method
     * @param jelly validated, uncompressed replacement Jelly bytes
     * @param expectedEtag required HTTP {@code If-Match} condition
     * @return the new storage ETag, or empty when the condition failed or the write lost a race
     */
    Optional<String> replace(String recordId, String documentName, byte[] jelly, String expectedEtag);

    /** Returns the Jelly object and its S3 headers, or empty when the named object is absent. */
    Optional<ResponseBytes<GetObjectResponse>> read(String recordId, String documentName);
}
