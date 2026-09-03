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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-tests development application startup and repeatable H2 initialization.
 *
 * <p>Running both SQL resources again models an application restart against an existing local
 * database. The fixed seed must remain unique and unchanged; production Cassandra initialization
 * is intentionally covered by its dedicated container test.</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("dev")
class DdbApiApplicationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @Sql({"/db/h2/schema.sql", "/db/h2/data.sql"})
    void databaseInitializationCanRunAgainWithoutChangingTheSeed() {
        var seedCount = jdbcClient
                .sql("SELECT COUNT(*) FROM record_metadata_by_id WHERE record_id = :recordId")
                .param("recordId", "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
                .query(Long.class)
                .single();

        assertThat(seedCount).isOne();
    }
}
