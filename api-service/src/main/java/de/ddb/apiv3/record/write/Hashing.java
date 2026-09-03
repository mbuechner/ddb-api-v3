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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Creates the cryptographic digest used for stored data hashes and strong ETags.
 *
 * <p>The algorithm is deliberately not runtime-configurable because clients and storage adapters
 * must observe the same validator in every deployment.</p>
 */
final class Hashing {

    private Hashing() {
    }

    /** Returns a lower-case hexadecimal SHA-256 digest. */
    static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest("SHA-256", value));
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandatory in every Java implementation.
            throw new IllegalStateException("The Java runtime does not provide " + algorithm, exception);
        }
    }
}
