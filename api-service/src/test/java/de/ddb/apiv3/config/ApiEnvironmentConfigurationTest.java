/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
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
 */

package de.ddb.apiv3.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Exercises the shipped YAML against OS-style variables without importing a developer's .env. */
class ApiEnvironmentConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void resolvesApiSettingsFromTheirOwnEnvironmentNamespace(String profile) throws IOException {
        var environment = environment(Map.ofEntries(
                Map.entry("DDB_API_PORT", "18080"),
                Map.entry("GATEWAY_PORT", "18081"),
                Map.entry("DDB_API_FORWARD_HEADERS_STRATEGY", "native"),
                Map.entry("DDB_API_FORWARD_HEADERS_NAMING", "x-forwarded"),
                Map.entry("DDB_API_LOG_FORMAT", "logstash"),
                Map.entry("DDB_API_ACCESS_LOG_ENABLED", "false"),
                Map.entry("DDB_API_ACCESS_LOG_LEVEL", "WARN"),
                Map.entry("DDB_API_PERFORMANCE_LOG_ENABLED", "false"),
                Map.entry("DDB_API_SLOW_REQUEST_THRESHOLD", "250ms"),
                Map.entry("DDB_API_CLIENT_ID_HEADER_NAME", "X-Api-Client"),
                Map.entry("DDB_API_S3_ENDPOINT", "http://storage.example:9000"),
                Map.entry("DDB_API_S3_REGION", "eu-west-1"),
                Map.entry("DDB_API_S3_BUCKET", "api-records"),
                Map.entry("DDB_API_S3_ACCESS_KEY", "test-access"),
                Map.entry("DDB_API_S3_SECRET_KEY", "test-secret"),
                Map.entry("DDB_API_S3_CREATE_BUCKET", "false")));
        load(environment, "application-" + profile + ".yaml");

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18080);
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(environment.getProperty("server.request.header.forwarded.naming")).isEqualTo("x-forwarded");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(environment.getProperty("logging.level.de.ddb.apiv3.access")).isEqualTo("WARN");
        assertThat(environment.getProperty("ddb.logging.access.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("ddb.logging.performance.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("ddb.logging.performance.slow-threshold")).isEqualTo("250ms");
        assertThat(environment.getProperty("ddb.logging.access.client-id-header-name")).isEqualTo("X-Api-Client");
        assertThat(environment.getProperty("ddb.storage.s3.endpoint")).isEqualTo("http://storage.example:9000");
        assertThat(environment.getProperty("ddb.storage.s3.region")).isEqualTo("eu-west-1");
        assertThat(environment.getProperty("ddb.storage.s3.bucket")).isEqualTo("api-records");
        assertThat(environment.getProperty("ddb.storage.s3.access-key")).isEqualTo("test-access");
        assertThat(environment.getProperty("ddb.storage.s3.secret-key")).isEqualTo("test-secret");
        assertThat(environment.getProperty("ddb.storage.s3.create-bucket", Boolean.class)).isFalse();
    }

    @Test
    void gatewayEnvironmentDoesNotChangeApiDefaults() throws IOException {
        var environment = environment(Map.ofEntries(
                Map.entry("GATEWAY_PORT", "18081"),
                Map.entry("GATEWAY_FORWARD_HEADERS_STRATEGY", "native"),
                Map.entry("GATEWAY_FORWARD_HEADERS_NAMING", "x-forwarded"),
                Map.entry("GATEWAY_LOG_FORMAT", "logstash"),
                Map.entry("GATEWAY_ACCESS_LOG_ENABLED", "false"),
                Map.entry("GATEWAY_ACCESS_LOG_LEVEL", "WARN"),
                Map.entry("GATEWAY_PERFORMANCE_LOG_ENABLED", "false"),
                Map.entry("GATEWAY_SLOW_REQUEST_THRESHOLD", "250ms"),
                Map.entry("GATEWAY_CLIENT_ID_HEADER_NAME", "X-Gateway-Client")));

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(environment.getProperty("server.request.header.forwarded.naming")).isEqualTo("rfc-7239");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("logging.level.de.ddb.apiv3.access")).isEqualTo("INFO");
        assertThat(environment.getProperty("ddb.logging.access.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ddb.logging.performance.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("ddb.logging.performance.slow-threshold")).isEqualTo("100ms");
        assertThat(environment.getProperty("ddb.logging.access.client-id-header-name")).isEqualTo("X-Client-Id");
    }

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("testEnvironment", variables));
        load(environment, "application.yaml");
        return environment;
    }

    private static void load(StandardEnvironment environment, String name) throws IOException {
        for (var source : new YamlPropertySourceLoader().load(name, new ClassPathResource(name))) {
            environment.getPropertySources().addFirst(source);
        }
    }
}
