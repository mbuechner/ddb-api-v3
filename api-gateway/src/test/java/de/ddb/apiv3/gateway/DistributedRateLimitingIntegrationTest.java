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

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves that independent gateway processes consume one client bucket stored in real Redis.
 *
 * <p>The three application contexts simulate horizontally scaled gateways and share one
 * standalone Redis Testcontainer. The test covers distributed Bucket4j state and expiry, not
 * Sentinel discovery or failover; those require a multi-node deployment test. It is skipped when
 * Docker is unavailable rather than silently substituting an in-memory backend.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class DistributedRateLimitingIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.10.1-alpine");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    private static final List<ConfigurableApplicationContext> GATEWAYS = new ArrayList<>();
    private static HttpServer backend;
    private static ExecutorService backendExecutor;

    @BeforeAll
    static void startInfrastructure() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendExecutor = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(backendExecutor);
        backend.createContext("/", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        backend.start();

        for (int instance = 0; instance < 3; instance++) {
            GATEWAYS.add(startGateway());
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        GATEWAYS.forEach(ConfigurableApplicationContext::close);
        if (backend != null) {
            backend.stop(0);
        }
        if (backendExecutor != null) {
            backendExecutor.close();
        }
    }

    @Test
    void sharesClientLimitAcrossThreeGatewayInstances() throws Exception {
        client(0)
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-a")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "0");

        client(1)
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-a")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "0")
                .expectBody()
                .isEmpty();

        client(2)
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-b")
                .exchange()
                .expectStatus()
                .isOk();

        String keys = REDIS.execInContainer(
                        "redis-cli", "--scan", "--pattern", "client:*")
                .getStdout();
        assertThat(keys).contains("client:client-a").contains("client:client-b");
        for (String key : keys.lines().filter(line -> !line.isBlank()).toList()) {
            long ttl = Long.parseLong(
                    REDIS.execInContainer("redis-cli", "TTL", key).getStdout().trim());
            assertThat(ttl).as("TTL of %s", key).isPositive();
        }
    }

    private static ConfigurableApplicationContext startGateway() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("APP_PROFILE", "prod");
        properties.put("GATEWAY_PORT", "0");
        properties.put("API_SERVICE_URI", "http://127.0.0.1:" + backend.getAddress().getPort());
        properties.put("REDIS_HOST", REDIS.getHost());
        properties.put("REDIS_PORT", REDIS.getMappedPort(6379));
        properties.put("REDIS_PASSWORD", "");
        properties.put("REDIS_TLS_ENABLED", "false");
        // One complete request per minute: the first call consumes all 60 accumulated tokens.
        properties.put("RATE_LIMIT_REPLENISH_RATE", "1");
        properties.put("RATE_LIMIT_BURST_CAPACITY", "60");
        properties.put("RATE_LIMIT_REQUESTED_TOKENS", "60");
        properties.put("CLIENT_ID_HEADER_ENABLED", "true");
        properties.put("management.endpoints.enabled-by-default", "false");
        properties.put("spring.main.banner-mode", "off");
        return new SpringApplicationBuilder(DdbApiGatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                // Test endpoints must win over an optional developer .env loaded by the app.
                .run(properties.entrySet().stream()
                        .map(entry -> "--%s=%s".formatted(entry.getKey(), entry.getValue()))
                        .toArray(String[]::new));
    }

    private static WebTestClient client(int gatewayIndex) {
        WebServerApplicationContext gateway = (WebServerApplicationContext) GATEWAYS.get(gatewayIndex);
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gateway.getWebServer().getPort())
                .build();
    }
}
