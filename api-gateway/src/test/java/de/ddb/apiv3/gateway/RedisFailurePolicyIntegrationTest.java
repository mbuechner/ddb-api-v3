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
package de.ddb.apiv3.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Documents the production fail-closed policy for the distributed rate-limit backend.
 *
 * <p>The selected port is released immediately before startup and is therefore expected to refuse
 * the connection. This is a focused startup test, not a runtime outage test: it ensures a
 * production gateway never falls back to process-local limits when Redis is unavailable.</p>
 */
class RedisFailurePolicyIntegrationTest {

    @Test
    void refusesToStartWhenTheConfiguredBackendIsUnavailable() throws IOException {
        int unavailablePort = unusedPort();

        assertThatThrownBy(() -> new SpringApplicationBuilder(DdbApiGatewayApplication.class)
                        .web(WebApplicationType.REACTIVE)
                        .run(
                                "--APP_PROFILE=prod",
                                "--GATEWAY_PORT=0",
                                "--REDIS_HOST=127.0.0.1",
                                "--REDIS_PORT=" + unavailablePort,
                                "--REDIS_PASSWORD=",
                                "--REDIS_TLS_ENABLED=false",
                                "--REDIS_CONNECT_TIMEOUT=100ms",
                                "--REDIS_COMMAND_TIMEOUT=100ms",
                                "--management.endpoints.enabled-by-default=false",
                                "--spring.main.banner-mode=off",
                                "--logging.level.root=OFF"))
                .hasRootCauseInstanceOf(ConnectException.class);
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
