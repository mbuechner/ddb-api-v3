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

import java.time.OffsetDateTime;

/**
 * Internal aggregate combining contract metadata with HTTP cache validators.
 *
 * @param metadata public record metadata
 * @param etag strong entity tag, including quotes as required by HTTP
 * @param lastModified timestamp used for conditional requests
 */
public record StoredRecord(StoredRecordMetadata metadata, String etag, OffsetDateTime lastModified) {
}
