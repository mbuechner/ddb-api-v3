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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

/**
 * Exercises the complete development gateway against an in-process HTTP backend without Redis.
 *
 * <p>A real random-port WebFlux server is required here because route filters, response headers
 * and global CORS handling are the behavior under test. The deliberately invalid Redis host also
 * guards against accidentally reintroducing a Redis dependency into the {@code dev} profile.
 * Bucket state is process-local, so this test does not make claims about multi-instance limits.</p>
 */
class LocalGatewayWithoutRedisIntegrationTest {

    private static ConfigurableApplicationContext gateway;
    private static HttpServer backend;
    private static ExecutorService backendExecutor;

    @BeforeAll
    static void startGatewayWithoutRedis() throws IOException {
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

        gateway = new SpringApplicationBuilder(DdbApiGatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--APP_PROFILE=dev",
                        "--GATEWAY_PORT=0",
                        "--API_SERVICE_URI=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--REDIS_HOST=redis-is-not-running.invalid",
                        "--RATE_LIMIT_REPLENISH_RATE=1",
                        "--RATE_LIMIT_BURST_CAPACITY=2",
                        "--RATE_LIMIT_REQUESTED_TOKENS=1",
                        "--RATE_LIMIT_REFILL_PERIOD=1h",
                        "--management.endpoints.web.exposure.include=health",
                        "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stopInfrastructure() {
        if (gateway != null) {
            gateway.close();
        }
        if (backend != null) {
            backend.stop(0);
        }
        if (backendExecutor != null) {
            backendExecutor.close();
        }
    }

    @Test
    void limitsEachClientIndependentlyAndStaysHealthyWithoutRedis() {
        webTestClient()
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-a")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "1")
                .expectHeader()
                .valueEquals("X-RateLimit-Replenish-Rate", "1")
                .expectHeader()
                .valueEquals("X-RateLimit-Burst-Capacity", "2")
                .expectHeader()
                .valueEquals("X-RateLimit-Requested-Tokens", "1")
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ok");

        webTestClient()
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-a")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "0");

        webTestClient()
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

        webTestClient()
                .get()
                .uri("/3/probe")
                .header("X-Client-Id", "client-b")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "1");

        webTestClient()
                .get()
                .uri("/openapi/probe")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist("X-RateLimit-Remaining")
                .expectHeader()
                .doesNotExist("X-RateLimit-Replenish-Rate")
                .expectHeader()
                .doesNotExist("X-RateLimit-Burst-Capacity")
                .expectHeader()
                .doesNotExist("X-RateLimit-Requested-Tokens");

        webTestClient()
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void addsCorsHeadersToProxiedResponses() {
        webTestClient()
                .get()
                .uri("/3/probe")
                .header("Origin", "https://client.example")
                .header("X-Client-Id", "cors-client")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "*")
                .expectHeader()
                .valueEquals(
                        "Access-Control-Expose-Headers",
                        "X-RateLimit-Remaining, X-RateLimit-Replenish-Rate, "
                                + "X-RateLimit-Burst-Capacity, X-RateLimit-Requested-Tokens, Retry-After")
                .expectHeader()
                .valueEquals("X-RateLimit-Remaining", "1")
                .expectHeader()
                .valueEquals("X-RateLimit-Replenish-Rate", "1")
                .expectHeader()
                .valueEquals("X-RateLimit-Burst-Capacity", "2")
                .expectHeader()
                .valueEquals("X-RateLimit-Requested-Tokens", "1");
    }

    @Test
    void answersCorsPreflightWithoutConsumingRateLimit() {
        webTestClient()
                .options()
                .uri("/3/probe")
                .header("Origin", "https://client.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "*")
                .expectHeader()
                .valueEquals("Access-Control-Allow-Methods", "GET")
                .expectHeader()
                .valueEquals("Access-Control-Allow-Headers", "Authorization")
                .expectHeader()
                .valueEquals("Access-Control-Max-Age", "3600")
                .expectHeader()
                .doesNotExist("X-RateLimit-Remaining");
    }

    private static WebTestClient webTestClient() {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }
}
