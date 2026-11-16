/* 
 *  Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 *  This code was made with help from OpenAI GTP-6.1 Sol Ultra.
 */

package de.ddb.apiv3.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, fake backend, no Redis: exercise routing, CORS and local token buckets together. */
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
            if (exchange.getRequestURI().getPath().equals("/records/alias-probe")) {
                exchange.getResponseHeaders().set("Location", "/3" + exchange.getRequestURI());
                exchange.sendResponseHeaders(308, -1);
                exchange.close();
                return;
            }
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("ETag", "\"stored-rdf\"");
            exchange.getResponseHeaders().set("Location", "/3/records/example");
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            exchange.getResponseHeaders().set("X-Request-Id", requestId);
            exchange.getResponseHeaders().set("X-Backend-Request-Id", requestId);
            exchange.getResponseHeaders().set("X-Backend-Host", exchange.getRequestHeaders().getFirst("Host"));
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(response.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            boolean delayed = exchange.getRequestURI().getPath().equals("/3/performance-delay");
            if (delayed) {
                delay(80);
            }
            exchange.sendResponseHeaders(200, response.length);
            if (delayed) {
                exchange.getResponseBody().write(response, 0, 1);
                exchange.getResponseBody().flush();
                delay(80);
                exchange.getResponseBody().write(response, 1, response.length - 1);
            } else {
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        backend.start();

        gateway = new SpringApplicationBuilder(DdbApiGatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--APP_PROFILE=dev",
                        "--DDB_DEV_WRITE_TOKEN=",
                        // Timing assertions must not inherit a developer's diagnostic switches.
                        "--GATEWAY_PERFORMANCE_LOG_ENABLED=true",
                        "--GATEWAY_SLOW_REQUEST_THRESHOLD=100ms",
                        "--GATEWAY_PORT=0",
                        "--GATEWAY_API_SERVICE_URI=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--GATEWAY_REDIS_HOST=redis-is-not-running.invalid",
                        "--GATEWAY_RATE_LIMIT_REPLENISH_RATE=1",
                        "--GATEWAY_RATE_LIMIT_BURST_CAPACITY=2",
                        "--GATEWAY_RATE_LIMIT_REQUESTED_TOKENS=1",
                        "--GATEWAY_RATE_LIMIT_REFILL_PERIOD=1h",
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

        for (final String path : new String[] {
                "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            webTestClient()
                    .get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("UP");
        }
    }

    @Test
    void forwardsVersionlessResourceRedirectsWithoutFollowingThemOrApplyingTheTargetQuota() {
        webTestClient().get().uri("/records/alias-probe?view=full").exchange()
                .expectStatus().isPermanentRedirect()
                .expectHeader().valueEquals("Location", "/3/records/alias-probe?view=full")
                .expectHeader().doesNotExist("X-RateLimit-Remaining");
        webTestClient().get().uri("/datasets/unknown").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("X-RateLimit-Remaining");
    }

    @Test
    void preservesThePublicHostForVersionedApiRequests() {
        webTestClient().get().uri("/3/probe")
                .header("Host", "public.example:8443")
                .header("X-Client-Id", "origin-client")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend-Host", "public.example:8443");
    }

    @Test
    void addsCorsHeadersToProxiedResponses() {
        webTestClient()
                .get()
                .uri("/3/probe")
                .header("Origin", "https://client.example")
                .header("X-Client-Id", "cors-client")
                .header("X-Request-Id", "gateway-test-request")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "*")
                .expectHeader()
                .value("Access-Control-Expose-Headers", value -> assertThat(value.split(",\\s*"))
                        .contains("ETag", "X-Record-ETag", "X-Item-ETag", "Location", "X-Request-Id", "X-RateLimit-Remaining",
                                "X-RateLimit-Replenish-Rate", "X-RateLimit-Burst-Capacity",
                                "X-RateLimit-Requested-Tokens", "Retry-After"))
                .expectHeader()
                .valueEquals("ETag", "\"stored-rdf\"")
                .expectHeader()
                .valueEquals("Location", "/3/records/example")
                .expectHeader()
                .valueEquals("X-Request-Id", "gateway-test-request")
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

    @Test
    void omitsSuccessfulProbeAccessEventsAndLogsFailures() {
        final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
        final var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (final String path : new String[] {
                    "/actuator/health/liveness", "/actuator/health/liveness?probe=1",
                    "/actuator/health/readiness", "/actuator/health/readiness?probe=1"}) {
                webTestClient().get().uri(path).exchange().expectStatus().isOk()
                        .expectBody().returnResult();
            }
            AvailabilityChangeEvent.publish(gateway, ReadinessState.REFUSING_TRAFFIC);
            webTestClient().get().uri("/actuator/health/readiness?probe=failed")
                    .exchange().expectStatus().isEqualTo(503).expectBody().returnResult();
            AvailabilityChangeEvent.publish(gateway, ReadinessState.ACCEPTING_TRAFFIC);
            AvailabilityChangeEvent.publish(gateway, LivenessState.BROKEN);
            webTestClient().get().uri("/actuator/health/liveness")
                    .exchange().expectStatus().isEqualTo(503).expectBody().returnResult();
            AvailabilityChangeEvent.publish(gateway, LivenessState.CORRECT);
            webTestClient().get().uri("/actuator/health/liveness-extra")
                    .exchange().expectStatus().isNotFound().expectBody().returnResult();
            webTestClient().get().uri("/actuator/health")
                    .exchange().expectStatus().isOk().expectBody().returnResult();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(appender.list.stream().map(LocalGatewayWithoutRedisIntegrationTest::ecsJson))
                        .extracting(event -> event.at("/url/path").asString())
                        .containsExactly("/actuator/health/readiness", "/actuator/health/liveness",
                                "/actuator/health/liveness-extra", "/actuator/health");
                assertThat(appender.list.stream().map(LocalGatewayWithoutRedisIntegrationTest::ecsJson))
                        .extracting(event -> event.at("/http/response/status_code").intValue())
                        .containsExactly(503, 503, 404, 200);
            });
        } finally {
            AvailabilityChangeEvent.publish(gateway, ReadinessState.ACCEPTING_TRAFFIC);
            AvailabilityChangeEvent.publish(gateway, LivenessState.CORRECT);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void emitsEcsAccessEventsAfterHttpCompletionWithoutLeakingSensitiveInput() {
        final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
        final var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                webTestClient().get().uri("/3/access-log-test?token=private-query-secret")
                        .header("X-Client-Id", "access-log-client")
                        .header("X-Request-Id", "gateway-test-request")
                        .header("X-Forwarded-For", "203.0.113.24")
                        .header("Forwarded", "for=203.0.113.25")
                        .header("Authorization", "Bearer private-authorization-secret")
                        .header("Cookie", "session=private-cookie-secret")
                        .header("User-Agent", "Access test agent")
                        .exchange().expectStatus().isEqualTo(attempt < 2 ? 200 : 429)
                        .expectBody().returnResult();
            }
            webTestClient().get().uri("/contexts/access-log-missing")
                    .header("X-Client-Id", "invalid/client-id")
                    .header("User-Agent", "x".repeat(513))
                    .exchange().expectStatus().isNotFound().expectBody().returnResult();
            webTestClient().get().uri("/records/alias-probe")
                    .exchange().expectStatus().isPermanentRedirect().expectBody().returnResult();
            webTestClient().head().uri("/3/access-log-head").header("X-Client-Id", "access-head-client")
                    .exchange().expectStatus().isOk().expectHeader().contentLength(15)
                    .expectBody().isEmpty();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(appender.list).hasSize(6));
            final var events = appender.list.stream().map(LocalGatewayWithoutRedisIntegrationTest::ecsJson).toList();
            assertThat(events).extracting(event -> event.at("/http/response/status_code").intValue())
                    .containsExactly(200, 200, 429, 404, 308, 200);
            final JsonNode successful = events.getFirst();
            assertThat(successful.at("/event/dataset").asString()).isEqualTo("http.access");
            assertThat(successful.at("/event/kind").asString()).isEqualTo("event");
            assertThat(successful.at("/http/request/method").asString()).isEqualTo("GET");
            assertThat(successful.at("/url/path").asString()).isEqualTo("/3/access-log-test");
            assertThat(successful.at("/source/ip").asString()).isEqualTo("127.0.0.1");
            assertThat(successful.at("/client/ip").asString()).isEqualTo("127.0.0.1");
            assertThat(successful.at("/network/protocol").asString()).isEqualTo("http");
            assertThat(successful.at("/http/version").asString()).isEqualTo("1.1");
            assertThat(successful.at("/labels/client_id").asString()).isEqualTo("access-log-client");
            assertThat(successful.at("/labels/client_id_source").asString()).isEqualTo("header");
            assertThat(successful.at("/http/request/id").asString()).isEqualTo("gateway-test-request");
            assertThat(successful.at("/labels/request_id_source").asString()).isEqualTo("response");
            assertThat(successful.at("/labels/response_content_type").asString()).isEqualTo("application/json");
            assertThat(successful.at("/user_agent/original").asString()).isEqualTo("Access test agent");
            assertThat(successful.at("/event/duration").isIntegralNumber()).isTrue();
            assertThat(successful.at("/event/duration").longValue()).isNotNegative();
            assertThat(successful.at("/source/port").intValue()).isPositive();
            assertThat(successful.at("/http/response/body/bytes").longValue()).isEqualTo(15);
            assertThat(events.get(2).at("/labels/request_id_source").asString()).isEqualTo("response");
            assertThat(events.get(2).at("/http/request/id").asString()).isNotBlank();
            assertThat(events.get(3).at("/labels/client_id").isMissingNode()).isTrue();
            assertThat(events.get(3).at("/user_agent/original").asString()).isEqualTo("x".repeat(512));
            assertThat(events.getLast().at("/http/request/method").asString()).isEqualTo("HEAD");
            assertThat(events.getLast().at("/http/response/body/bytes").longValue()).isZero();
            assertThat(events.toString()).doesNotContain("private-query-secret", "private-authorization-secret",
                    "private-cookie-secret", "203.0.113.24", "203.0.113.25", "invalid/client-id");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void keepsConcurrentRequestFieldsSeparateAndGeneratedRequestIdsDistinct() throws Exception {
        final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
        final var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try (final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = IntStream.range(0, 12).mapToObj(index -> requests.submit(() ->
                    webTestClient().get().uri("/contexts/access-parallel/" + index)
                            .header("X-Client-Id", "parallel-" + index)
                            .exchange().expectStatus().isNotFound().expectBody().returnResult())).toList();
            for (final var future : futures) {
                future.get();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(appender.list).hasSize(12));
            final var events = appender.list.stream().map(LocalGatewayWithoutRedisIntegrationTest::ecsJson).toList();
            assertThat(events).extracting(event -> event.at("/http/request/id").asString()).doesNotHaveDuplicates();
            assertThat(events).allSatisfy(event -> {
                final String path = event.at("/url/path").asString();
                final String suffix = path.substring(path.lastIndexOf('/') + 1);
                assertThat(event.at("/labels/client_id").asString()).isEqualTo("parallel-" + suffix);
                assertThat(event.at("/labels/request_id_source").asString()).isEqualTo("response");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void correlatesTimingsAndSeparatesBackendHeadersFromBodyTransferIncludingEarlyRejections() {
        final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.performance");
        final var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try {
            webTestClient().get().uri("/3/performance-delay?token=private-query-secret")
                    .header("X-Client-Id", "performance-client")
                    .header("X-Request-Id", "performance-known-id")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Request-Id", "performance-known-id")
                    .expectHeader().valueEquals("X-Backend-Request-Id", "performance-known-id")
                    .expectBody().returnResult();
            var invalid = webTestClient().get().uri("/3/performance-invalid-id")
                    .header("X-Client-Id", "performance-client")
                    .header("X-Request-Id", "invalid/id?private-header-secret")
                    .exchange().expectStatus().isOk()
                    .expectHeader().valueMatches("X-Request-Id", "[a-f0-9-]{36}")
                    .expectBody().returnResult();
            assertThat(invalid.getResponseHeaders().getFirst("X-Backend-Request-Id"))
                    .isEqualTo(invalid.getResponseHeaders().getFirst("X-Request-Id"));
            webTestClient().get().uri("/3/performance-limit")
                    .header("X-Client-Id", "performance-client")
                    .header("X-Request-Id", "performance-denied-id")
                    .exchange().expectStatus().isEqualTo(429)
                    .expectHeader().valueEquals("X-Request-Id", "performance-denied-id")
                    .expectBody().returnResult();
            webTestClient().post().uri("/3/records/unauthorized")
                    .header("X-Request-Id", "performance-auth-id")
                    .exchange().expectStatus().isUnauthorized()
                    .expectHeader().valueEquals("X-Request-Id", "performance-auth-id")
                    .expectBody().returnResult();
            webTestClient().get().uri("/contexts/performance-missing")
                    .header("X-Request-Id", "performance-missing-id")
                    .exchange().expectStatus().isNotFound().expectBody().returnResult();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(appender.list).hasSize(5));
            var events = appender.list.stream().map(LocalGatewayWithoutRedisIntegrationTest::ecsJson).toList();
            var delayed = events.getFirst();
            assertThat(delayed.at("/event/dataset").asString()).isEqualTo("http.performance");
            assertThat(delayed.at("/http/request/id").asString()).isEqualTo("performance-known-id");
            assertThat(delayed.at("/performance/backend_headers_ms").doubleValue()).isGreaterThanOrEqualTo(60);
            assertThat(delayed.at("/performance/response_transfer_ms").doubleValue()).isGreaterThanOrEqualTo(60);
            assertThat(delayed.at("/performance/total_ms").doubleValue()).isGreaterThanOrEqualTo(140);
            assertThat(delayed.at("/performance/slow").booleanValue()).isTrue();
            assertThat(delayed.at("/performance/rate_limit_ms").doubleValue()).isNotNegative();
            assertThat(delayed.at("/labels/rate_limit_backend").asString()).isEqualTo("local");
            assertThat(delayed.at("/labels/rate_limit_outcome").asString()).isEqualTo("allowed");
            assertThat(events.get(2).at("/labels/rate_limit_outcome").asString()).isEqualTo("denied");
            assertThat(events.get(2).at("/performance/backend_headers_ms").isMissingNode()).isTrue();
            assertThat(events.get(3).at("/performance/rate_limit_ms").isMissingNode()).isTrue();
            assertThat(events).extracting(event -> event.at("/http/response/status_code").intValue())
                    .containsExactly(200, 200, 429, 401, 404);
            assertThat(events.toString()).doesNotContain("private-query-secret", "private-header-secret", "invalid/id");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void delay(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fake backend", exception);
        }
    }

    private static JsonNode ecsJson(ILoggingEvent event) {
        final var encoder = new StructuredLogEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setFormat("ecs");
        encoder.start();
        try {
            return JsonMapper.builder().build().readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    private static WebTestClient webTestClient() {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }
}
