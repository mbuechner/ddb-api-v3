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

package de.ddb.apiv3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.ddb.apiv3.config.ApiVersionRedirectController;
import de.ddb.apiv3.config.HttpAccessLogConfiguration;
import de.ddb.apiv3.config.WriteSecurityConfiguration;
import de.ddb.apiv3.error.ApiExceptionHandler;
import de.ddb.apiv3.support.WriteTestCredentials;
import jakarta.json.Json;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Real HTTP and Boot's actual ECS encoder: one safe event per completed response. */
@SpringBootTest(classes = HttpAccessLogIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "management.endpoint.health.group.readiness.include=readinessState",
                "ddb.security.dev-write-token=" + WriteTestCredentials.TOKEN,
                "server.forward-headers-strategy=native"})
class HttpAccessLogIntegrationTest {

    private static final Logger ACCESS = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");

    @LocalServerPort
    private int port;

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void captureAccessEvents() {
        events.list = new CopyOnWriteArrayList<>();
        events.start();
        ACCESS.addAppender(events);
    }

    @AfterEach
    void stopCapturingAccessEvents() {
        ACCESS.detachAppender(events);
        events.stop();
    }

    @Test
    void recordsCompletedTransferWithTypedFieldsAndWithoutSecrets() throws Exception {
        String secret = "private-rdf-and-token";
        String userAgent = "test-agent/1.0 \"quoted\" \\ escaped";
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("/3/records/log-test?token=" + secret)
                    .header("X-Client-Id", "harvester:42")
                    .header("User-Agent", userAgent)
                    .header("Authorization", WriteTestCredentials.AUTHORIZATION)
                    .header("Cookie", "session=" + secret)
                    .header("Forwarded", "for=203.0.113.99;host=spoof.example;proto=https")
                    .header("X-Forwarded-For", "203.0.113.99")
                    .header("Content-Type", "application/rdf+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(secret)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        var event = onlyEvent();
        var fields = fields(event);
        assertThat(fields).containsEntry("event.dataset", "http.access")
                .containsEntry("event.kind", "event")
                .containsEntry("http.request.method", "POST")
                .containsEntry("url.path", "/3/records/log-test")
                .containsEntry("http.response.status_code", 200)
                .containsEntry("http.request.body.bytes", (long) secret.length())
                .containsEntry("http.response.body.bytes", 2L)
                .containsEntry("source.ip", "127.0.0.1")
                .containsEntry("client.ip", "203.0.113.99")
                .containsEntry("labels.client_id", "harvester:42")
                .containsEntry("labels.client_id_source", "header")
                .containsEntry("user_agent.original", userAgent)
                .containsEntry("labels.request_content_type", "application/rdf+xml")
                .containsEntry("network.protocol", "http")
                .containsEntry("http.version", "1.1");
        assertThat((Long) fields.get("event.duration")).isPositive();
        assertThat((Integer) fields.get("source.port")).isPositive();

        String json = ecs(event);
        assertThat(json).doesNotContain(secret, WriteTestCredentials.TOKEN, "Authorization", "session=")
                .contains("\"@timestamp\"");
        assertThat(json.lines().count()).isEqualTo(1);
        try (var reader = Json.createReader(new StringReader(json))) {
            var object = reader.readObject();
            assertThat(object.getJsonObject("service").getString("name")).isEqualTo("ddb-api-v3-service");
            assertThat(object.getJsonObject("user_agent").getString("original")).isEqualTo(userAgent);
            assertThat(object.getJsonObject("http").getJsonObject("response")
                    .getInt("status_code")).isEqualTo(200);
        }
    }

    @Test
    void logsRedirectsNotFoundAndServerErrorsExactlyOnce() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(request("/records/missing").build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(308);
            assertThat(client.send(request("/3/missing").build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(404);
            var failure = client.send(request("/3/log-test/failure").build(), HttpResponse.BodyHandlers.discarding());
            assertThat(failure.statusCode()).isEqualTo(500);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.list).hasSize(3));
            assertThat(fields(events.list.get(0))).containsEntry("http.response.status_code", 308);
            assertThat(fields(events.list.get(1))).containsEntry("http.response.status_code", 404);
            assertThat(fields(events.list.get(2))).containsEntry("http.response.status_code", 500)
                    .containsEntry("http.request.id", failure.headers().firstValue("X-Request-Id").orElseThrow())
                    .containsEntry("labels.request_id_source", "response");
        }
    }

    @Test
    void measuresFullHandlerTimeAndBoundsUntrustedHeaderValues() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            client.send(request("/3/log-test/slow")
                    .header("X-Client-Id", "x".repeat(129))
                    .header("User-Agent", "a".repeat(1000)).build(), HttpResponse.BodyHandlers.discarding());
        }
        var fields = fields(onlyEvent());
        assertThat((Long) fields.get("event.duration")).isGreaterThanOrEqualTo(20_000_000L);
        assertThat(fields).doesNotContainKey("labels.client_id");
        assertThat((String) fields.get("user_agent.original")).hasSize(512);
    }

    @Test
    void concurrentRequestsNeverShareClientContextOrNativeIds() {
        try (var client = HttpClient.newHttpClient()) {
            var requests = IntStream.range(0, 16).mapToObj(index -> client.sendAsync(
                    request("/3/log-test/" + index).header("X-Client-Id", "client-" + index).build(),
                    HttpResponse.BodyHandlers.discarding())).toList();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.list).hasSize(16));
            var fields = events.list.stream().map(HttpAccessLogIntegrationTest::fields).toList();
            fields.forEach(event -> {
                String path = (String) event.get("url.path");
                assertThat(event).containsEntry("labels.client_id", "client-" + path.substring(path.lastIndexOf('/') + 1));
            });
            assertThat(fields.stream().map(event -> event.get("http.request.id")).distinct()).hasSize(16);
        }
    }

    @Test
    void omitsUnresolvedForwardedHostnamesWithoutResolvingThem() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            client.send(request("/3/log-test/ok").header("Forwarded", "for=unresolvable.invalid").build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        assertThat(fields(onlyEvent())).containsEntry("source.ip", "127.0.0.1")
                .doesNotContainKey("client.ip");
    }

    @Test
    void accessLoggingCanBeDisabledWithoutReplacingApplicationLogging() {
        new ApplicationContextRunner().withUserConfiguration(HttpAccessLogConfiguration.class)
                .withPropertyValues("ddb.logging.access.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("httpAccessLog"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private ILoggingEvent onlyEvent() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.list).hasSize(1));
        return events.list.getFirst();
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        var result = new HashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> result.put(pair.key, pair.value));
        return result;
    }

    private static String ecs(ILoggingEvent event) {
        var encoder = new StructuredLogEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setFormat("ecs");
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    @Import({HttpAccessLogConfiguration.class, ApiVersionRedirectController.class, ApiExceptionHandler.class,
            WriteSecurityConfiguration.class})
    static class Application {

        @Bean
        static RouterFunction<ServerResponse> logTestRoutes() {
            return RouterFunctions.route()
                    .POST("/3/records/log-test", request -> {
                        request.body(String.class);
                        return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body("ok");
                    })
                    .GET("/3/log-test/failure", _ -> {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Test failure");
                    })
                    .GET("/3/log-test/slow", _ -> {
                        Thread.sleep(20);
                        return ServerResponse.ok().body("ok");
                    })
                    .GET("/3/log-test/{id}", _ -> ServerResponse.ok().body("ok"))
                    .build();
        }
    }
}
