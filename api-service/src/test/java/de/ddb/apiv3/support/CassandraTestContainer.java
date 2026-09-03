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
package de.ddb.apiv3.support;

import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/** Centralizes the pinned Cassandra image and packaged reference schema used by integration tests. */
public final class CassandraTestContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("cassandra:5.0.9");

    private CassandraTestContainer() {
    }

    /**
     * Creates a single-node Cassandra instance with the production reference schema.
     *
     * <p>A new container is used per application/test run to avoid leaked state. It validates real
     * CQL and driver behavior but intentionally does not simulate replication or node failures.</p>
     */
    public static CassandraContainer create() {
        return new CassandraContainer(IMAGE).withInitScript("db/cassandra/schema.cql");
    }
}
