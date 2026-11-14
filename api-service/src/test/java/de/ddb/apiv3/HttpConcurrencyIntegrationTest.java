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
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.ddb.apiv3.support.S3TestSupport;
import jakarta.json.Json;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Real sockets: bounded work/queue, predictable overload and probes outside the RDF gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ddb.api.concurrency.max-requests=1", "ddb.api.concurrency.max-queued-requests=1",
        "ddb.api.concurrency.queue-timeout=2s"})
@ActiveProfiles("dev")
@Import(HttpConcurrencyIntegrationTest.BlockingRoute.class)
class HttpConcurrencyIntegrationTest extends S3TestSupport {

    private static final Logger ACCESS = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");

    private final ListAppender<ILoggingEvent> accessEvents = new ListAppender<>();

    @LocalServerPort
    private int port;

    @Autowired
    private WebServerApplicationContext context;

    @Autowired
    private Gate gate;

    @BeforeEach
    void captureAccessEvents() {
        accessEvents.list = new CopyOnWriteArrayList<>();
        accessEvents.start();
        ACCESS.addAppender(accessEvents);
    }

    @AfterEach
    void stopCapturingAccessEvents() {
        ACCESS.detachAppender(accessEvents);
        accessEvents.stop();
    }

    @Test
    void boundsTheQueueKeepsProbesAliveAndReleasesCapacityAfterTimeout() throws Exception {
        var qos = ((JettyWebServer) context.getWebServer()).getServer().getDescendant(QoSHandler.class);
        assertThat(qos).isNotNull();
        try (var client = HttpClient.newHttpClient()) {
            var active = client.sendAsync(request("/3/concurrency-test?block=true"),
                    HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(gate.entered().await(5, TimeUnit.SECONDS)).isTrue();
                var queued = client.sendAsync(request("/3/concurrency-test"),
                        HttpResponse.BodyHandlers.ofString());
                await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5))
                        .until(() -> qos.getSuspendedRequestCount() == 1);

                assertOverload(client.send(request("/3/concurrency-test"), HttpResponse.BodyHandlers.ofString()));
                var health = client.send(request("/actuator/health/liveness"), HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                var documentation = client.send(request("/openapi/openapi.yaml"), HttpResponse.BodyHandlers.ofString());
                assertThat(documentation.statusCode()).isEqualTo(200);
                assertOverload(queued.get(5, TimeUnit.SECONDS));
                assertThat(gate.executed().get()).isEqualTo(1);
            } finally {
                gate.release().countDown();
            }
            assertThat(active.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(client.send(request("/3/concurrency-test"), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
            assertThat(gate.executed().get()).isEqualTo(2);
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).build();
    }

    private void assertOverload(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        try (var reader = Json.createReader(new StringReader(response.body()))) {
            var problem = reader.readObject();
            assertThat(problem.getInt("status")).isEqualTo(503);
            // RFC 9457 defaults an omitted type to about:blank; Spring omits it here.
            assertThat(problem.getString("type", "about:blank")).isEqualTo("about:blank");
            assertThat(response.headers().firstValue("X-Request-Id")).contains(problem.getString("requestId"));
            String requestId = problem.getString("requestId");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(accessEvents.list).anySatisfy(event -> {
                        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                            assertThat(pair.key).isEqualTo("http.request.id");
                            assertThat(pair.value).isEqualTo(requestId);
                        });
                        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                            assertThat(pair.key).isEqualTo("http.response.status_code");
                            assertThat(pair.value).isEqualTo(503);
                        });
                    }));
        }
    }

    private record Gate(CountDownLatch entered, CountDownLatch release, AtomicInteger executed) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingRoute {

        @Bean
        static Gate gate() {
            return new Gate(new CountDownLatch(1), new CountDownLatch(1), new AtomicInteger());
        }

        @Bean
        static RouterFunction<ServerResponse> blockingRoute(Gate gate) {
            return RouterFunctions.route().GET("/3/concurrency-test", request -> {
                gate.executed().incrementAndGet();
                if (request.param("block").isPresent()) {
                    gate.entered().countDown();
                    if (!gate.release().await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test did not release the active request");
                    }
                }
                return ServerResponse.ok().body("ok");
            }).build();
        }
    }
}
