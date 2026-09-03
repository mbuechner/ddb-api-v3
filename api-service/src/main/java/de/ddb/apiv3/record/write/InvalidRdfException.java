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
package de.ddb.apiv3.record.write;

/** Raised when an uploaded RDF/XML document cannot be parsed completely by Apache Jena. */
public final class InvalidRdfException extends RuntimeException {

    /**
     * Wraps Jena's parser failure without exposing a parser-specific exception in the HTTP layer.
     *
     * @param cause original parser failure retained for diagnostics
     */
    public InvalidRdfException(Throwable cause) {
        super("The content part is not valid RDF/XML", cause);
    }
}
