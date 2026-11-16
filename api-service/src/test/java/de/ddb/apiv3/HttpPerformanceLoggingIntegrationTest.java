/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */
package de.ddb.apiv3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestServer;
import de.ddb.apiv3.support.S3TestSupport;
import de.ddb.apiv3.support.WriteTestCredentials;
import jakarta.json.Json;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Real HTTP, the real S3 SDK, RDF conversion and the existing one-per-response access event. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ddb.logging.performance.slow-threshold=40ms")
@ActiveProfiles("dev")
class HttpPerformanceLoggingIntegrationTest extends S3TestSupport {

    private static final Logger ACCESS = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
    private static final String ID = "QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    @LocalServerPort
    private int port;
    @Autowired
    private RdfCodec codec;

    @BeforeEach
    void capture() {
        events.list = new CopyOnWriteArrayList<>();
        events.start();
        ACCESS.addAppender(events);
    }

    @AfterEach
    void stop() {
        STORAGE.setReadBodyDelay(Duration.ZERO);
        ACCESS.detachAppender(events);
        events.stop();
    }

    @Test
    void attributesDelayedS3BodyToS3AndCorrelatesWithTheGatewayId() throws Exception {
        STORAGE.storeObject(ID + "/record", codec.toJelly(S3TestServer.DEMO_RDF, "https://example.org/record"),
                RdfCodec.JELLY_CONTENT_TYPE);
        STORAGE.setReadBodyDelay(Duration.ofMillis(60));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request().header("X-Request-Id", "diagnostic:gateway-42").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Request-Id")).contains("diagnostic:gateway-42");
        }
        var fields = onlyEvent();
        assertThat(fields).containsEntry("http.request.id", "diagnostic:gateway-42")
                .containsEntry("performance.slow", true);
        assertThat((Double) fields.get("performance.s3_read_ms")).isGreaterThanOrEqualTo(50.0);
        assertThat((Double) fields.get("performance.jelly_decode_ms")).isNotNegative();
        assertThat((Double) fields.get("performance.rdf_serialize_ms")).isNotNegative();
        assertThat((Double) fields.get("performance.capacity_wait_ms")).isNotNegative();
        assertThat((Double) fields.get("performance.application_ms"))
                .isGreaterThanOrEqualTo((Double) fields.get("performance.s3_read_ms"));
        assertThat((Double) fields.get("performance.total_ms"))
                .isGreaterThanOrEqualTo((Double) fields.get("performance.application_ms"));
        assertThat(fields).doesNotContainKeys("performance.s3_write_ms", "performance.rdf_parse_ms");
    }

    @Test
    void logsFailedDecodingAndKeepsTheProblemId() throws Exception {
        STORAGE.storeObject(ID + "/record", new byte[] {0}, RdfCodec.JELLY_CONTENT_TYPE);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request().header("X-Request-Id", "diagnostic:corrupt").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("X-Request-Id")).contains("diagnostic:corrupt");
            try (var reader = Json.createReader(new StringReader(response.body()))) {
                assertThat(reader.readObject().getString("requestId")).isEqualTo("diagnostic:corrupt");
            }
        }
        var fields = onlyEvent();
        assertThat(fields).containsEntry("http.request.id", "diagnostic:corrupt")
                .containsKeys("performance.s3_read_ms", "performance.jelly_decode_ms")
                .doesNotContainKey("performance.rdf_serialize_ms");
    }

    @Test
    void capturesUploadStagesAndReplacesInvalidRequestIds() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request().header("X-Request-Id", "invalid id with spaces")
                    .header("Authorization", WriteTestCredentials.AUTHORIZATION)
                    .header("Content-Type", "application/rdf+xml")
                    .header("If-Match", "*")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(S3TestServer.DEMO_RDF)).build(),
                    HttpResponse.BodyHandlers.ofString());
            // The conditional write may miss, but all upload/conversion/S3 stages still ran.
            assertThat(response.statusCode()).isIn(204, 412);
            assertThat(response.headers().firstValue("X-Request-Id").orElseThrow())
                    .matches("[0-9a-f-]{36}");
        }
        assertThat(onlyEvent()).containsKeys("performance.upload_read_ms", "performance.rdf_parse_ms",
                "performance.jelly_encode_ms", "performance.s3_write_ms");
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/3/records/" + ID))
                .timeout(Duration.ofSeconds(10));
    }

    private Map<String, Object> onlyEvent() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.list).hasSize(1));
        var result = new HashMap<String, Object>();
        events.list.getFirst().getKeyValuePairs().forEach(pair -> result.put(pair.key, pair.value));
        return result;
    }
}
