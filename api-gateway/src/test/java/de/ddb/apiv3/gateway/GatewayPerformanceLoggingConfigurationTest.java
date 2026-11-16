/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package de.ddb.apiv3.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.Bucket4jRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;

class GatewayPerformanceLoggingConfigurationTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.performance");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capturePerformanceEvents() {
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void identifiesRedisFailClosedFallbackWithoutLoggingClientBucketOrExceptionMessages() {
        var delegate = mock(Bucket4jRateLimiter.class);
        when(delegate.isAllowed("records", "private-client-bucket"))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of("X-RateLimit-Remaining", "-1"))));
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var observed = GatewayPerformanceLoggingConfiguration.observedBucket4jRateLimiter(delegate, environment);
        var filter = timelineFilter(true, Duration.ofMillis(100));
        var exchange = exchange();

        filter.filter(exchange, current -> observed.isAllowed("records", "private-client-bucket")
                .flatMap(result -> {
                    current.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return current.getResponse().setComplete();
                })).block();

        var fields = fields();
        assertThat(fields).containsEntry("labels.rate_limit_backend", "redis")
                .containsEntry("labels.rate_limit_outcome", "backend_error")
                .containsEntry("http.request.id", "timing-unit-id")
                .containsEntry("http.response.status_code", 429)
                .containsEntry("event.outcome", "failure");
        assertThat((Double) fields.get("performance.rate_limit_ms")).isNotNegative();
        assertThat(fields.toString()).doesNotContain("private-client-bucket");
    }

    @Test
    void recordsCancellationWithAnUnfinishedLimiterAndNoInventedBackendOrStatus() {
        var delegate = mock(Bucket4jRateLimiter.class);
        when(delegate.isAllowed("records", "client")).thenReturn(Mono.never());
        var observed = GatewayPerformanceLoggingConfiguration.observedBucket4jRateLimiter(delegate, new MockEnvironment());
        var filter = timelineFilter(true, Duration.ofMillis(100));
        var subscription = filter.filter(exchange(), current -> observed.isAllowed("records", "client").then()).subscribe();
        subscription.dispose();

        assertThat(fields()).containsEntry("labels.completion_signal", "CANCEL")
                .containsEntry("labels.rate_limit_outcome", "cancelled")
                .containsEntry("event.outcome", "unknown")
                .doesNotContainKeys("performance.backend_headers_ms", "http.response.status_code");
    }

    @Test
    void recordsErrorTypeWithoutIncludingPotentiallySensitiveMessages() {
        var filter = timelineFilter(true, Duration.ofMillis(100));
        filter.filter(exchange(), current ->
                Mono.error(new IllegalStateException("private-upstream-url-token"))).block();

        assertThat(fields()).containsEntry("error.type", IllegalStateException.class.getName())
                .containsEntry("event.outcome", "failure").containsEntry("http.response.status_code", 500);
        assertThat(fields().toString()).doesNotContain("private-upstream-url-token");
    }

    @Test
    void disablingPerformanceLoggingKeepsRequestCorrelationWithoutCollectingEvents() {
        var filter = timelineFilter(false, Duration.ofMillis(100));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/3/probe")
                .header("X-Request-Id", "invalid/id").build());

        filter.filter(exchange, current -> {
            String id = current.getRequest().getHeaders().getFirst("X-Request-Id");
            assertThat(id).matches("[a-f0-9-]{36}");
            assertThat(current.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
            return current.getResponse().setComplete();
        }).block();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void aBodyFailureRemainsAFailureAfterSuccessfulHeadersWereCommitted() {
        var filter = timelineFilter(true, Duration.ofMillis(100));
        assertThatThrownBy(() -> filter.filter(exchange(), current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete().then(Mono.error(new IllegalStateException("body failed")));
        }).block()).isInstanceOf(IllegalStateException.class);

        assertThat(fields()).containsEntry("http.response.status_code", 200)
                .containsEntry("event.outcome", "failure");
    }

    @Test
    void includesAsynchronousExceptionHandlingAndItsFinalStatus() {
        var filter = timelineFilter(true, Duration.ofMillis(50), (current, error) ->
                Mono.delay(Duration.ofMillis(100)).then(Mono.defer(() -> {
                    current.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return current.getResponse().setComplete();
                })));

        filter.filter(exchange(), current -> Mono.error(new IllegalStateException("handled later"))).block();

        assertThat(fields()).containsEntry("http.response.status_code", 503)
                .containsEntry("labels.completion_signal", "ON_COMPLETE")
                .containsEntry("event.outcome", "failure")
                .containsEntry("performance.slow", true);
        assertThat((Double) fields().get("performance.total_ms")).isGreaterThanOrEqualTo(90);
    }

    @Test
    void excludesAsynchronousErrorResponseTimeFromFailedBackendWait() {
        var backend = GatewayPerformanceLoggingConfiguration.gatewayBackendTimeline();
        var filter = timelineFilter(true, Duration.ofMillis(50), (current, error) ->
                Mono.delay(Duration.ofMillis(100)).then(Mono.defer(() -> {
                    current.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return current.getResponse().setComplete();
                })));

        filter.filter(exchange(), current -> backend.filter(current,
                routed -> Mono.error(new IllegalStateException("connection failed")))).block();

        var fields = fields();
        assertThat(fields).containsEntry("http.response.status_code", 503)
                .containsKey("performance.backend_wait_ms")
                .doesNotContainKeys("performance.backend_headers_ms", "performance.response_transfer_ms");
        // Compare intervals from the same timeline; scheduler noise can extend either phase.
        // The delayed error-body phase must remain outside the completed backend-wait span.
        assertThat((Double) fields.get("performance.total_ms") - (Double) fields.get("performance.backend_wait_ms"))
                .isGreaterThanOrEqualTo(90);
    }

    @Test
    void doesNotSuppressFailedHealthProbesAfterSuccessfulHeadersWereCommitted() {
        var filter = timelineFilter(true, Duration.ofMillis(100));
        var health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health/readiness"));

        assertThatThrownBy(() -> filter.filter(health, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete().then(Mono.error(new IllegalStateException("body failed")));
        }).block()).isInstanceOf(IllegalStateException.class);

        assertThat(fields()).containsEntry("http.response.status_code", 200)
                .containsEntry("event.outcome", "failure");
    }

    @Test
    void doesNotSuppressCancelledHealthProbes() {
        var filter = timelineFilter(true, Duration.ofMillis(100));
        var health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health/liveness"));
        var subscription = filter.filter(health, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.never();
        }).subscribe();
        subscription.dispose();

        assertThat(fields()).containsEntry("labels.completion_signal", "CANCEL")
                .containsEntry("event.outcome", "unknown");
    }

    @Test
    void validatesTheSameMinimumThresholdAsTheApi() {
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> GatewayPerformanceLoggingConfiguration.gatewayHttpTimeline(true, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1ms");
        }
    }

    private static WebFilter timelineFilter(boolean enabled, Duration threshold, WebExceptionHandler... handlers) {
        return (exchange, chain) -> {
            var builder = WebHttpHandlerBuilder.webHandler(chain::filter)
                    .filter(GatewayPerformanceLoggingConfiguration.gatewayRequestTimeline())
                    .exceptionHandler(handlers);
            GatewayPerformanceLoggingConfiguration.gatewayHttpTimeline(enabled, threshold).customize(builder);
            return builder.build().handle(exchange.getRequest(), exchange.getResponse());
        };
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/3/probe?token=private-query")
                .header("X-Request-Id", "timing-unit-id").build());
    }

    private Map<String, Object> fields() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(appender.list).hasSize(1));
        return appender.list.getFirst().getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
