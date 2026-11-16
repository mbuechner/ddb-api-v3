/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package de.ddb.apiv3.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webflux.autoconfigure.WebHttpHandlerBuilderCustomizer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyRoutingFilter;
import org.springframework.cloud.gateway.filter.ratelimit.Bucket4jRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** One per-exchange timeline; thread locals and pooled connection attributes are not request scopes. */
@Configuration(proxyBeanMethods = false)
final class GatewayPerformanceLoggingConfiguration {

    static final String REQUEST_ID = "X-Request-Id";
    private static final String TIMELINE = GatewayPerformanceLoggingConfiguration.class.getName();
    private static final Logger PERFORMANCE = LoggerFactory.getLogger("de.ddb.apiv3.performance");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    @Bean
    static WebHttpHandlerBuilderCustomizer gatewayHttpTimeline(
            @Value("${ddb.logging.performance.enabled:true}") boolean enabled,
            @Value("${ddb.logging.performance.slow-threshold:100ms}") Duration slowThreshold) {
        Assert.isTrue(slowThreshold.compareTo(Duration.ofMillis(1)) >= 0,
                "ddb.logging.performance.slow-threshold must be at least 1ms");
        long slowThresholdNanos = slowThreshold.toNanos();
        return builder -> builder.httpHandlerDecorator(handler -> httpTimeline(handler, enabled, slowThresholdNanos));
    }

    @Bean
    static WebFilter gatewayRequestTimeline() {
        return new RequestTimelineFilter();
    }

    private static HttpHandler httpTimeline(HttpHandler handler, boolean enabled, long slowThresholdNanos) {
        return (request, response) -> Mono.defer(() -> {
            String offered = request.getHeaders().getFirst(REQUEST_ID);
            String id = offered != null && offered.length() <= 128 && IDENTIFIER.matcher(offered).matches()
                    ? offered : UUID.randomUUID().toString();
            ServerHttpRequest correlated = request.mutate().headers(headers -> headers.set(REQUEST_ID, id)).build();
            // Correlation is not identity. Normalize before security and retain it on outer error responses too.
            response.getHeaders().set(REQUEST_ID, id);
            response.beforeCommit(() -> {
                response.getHeaders().set(REQUEST_ID, id);
                return Mono.empty();
            });
            if (!enabled || !PERFORMANCE.isInfoEnabled()) {
                return handler.handle(correlated, response);
            }
            var timeline = new Timeline(id, slowThresholdNanos);
            // A WebFilter completes before outer WebExceptionHandlers finish an asynchronous error body.
            // Observe the complete HttpHandler instead, with no scheduler hop or body buffering.
            return handler.handle(correlated, response)
                    .doOnError(error -> timeline.error = error)
                    .doFinally(signal -> timeline.log(correlated, response, signal))
                    .contextWrite(context -> context.put(TIMELINE, timeline));
        });
    }

    @Bean
    static GlobalFilter gatewayBackendTimeline() {
        return new BackendTimelineFilter();
    }

    @Bean
    @Primary
    static RateLimiter<Bucket4jRateLimiter.Config> observedBucket4jRateLimiter(
            @Lazy Bucket4jRateLimiter bucket4jRateLimiter, Environment environment) {
        // Instrumentation must not initialize Redis before security has validated the deployment's trust configuration.
        String backend = environment.acceptsProfiles(Profiles.of("prod")) ? "redis" : "local";
        return new ObservedRateLimiter(bucket4jRateLimiter, backend);
    }

    private static final class RequestTimelineFilter implements WebFilter, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            return Mono.deferContextual(context -> {
                Timeline timeline = context.getOrDefault(TIMELINE, null);
                if (timeline == null) {
                    return chain.filter(exchange);
                }
                timeline.exchange = exchange;
                exchange.getAttributes().put(TIMELINE, timeline);
                // Reactor Context follows the subscription across event loops; MDC would not.
                return chain.filter(exchange).doOnError(error -> timeline.error = error);
            });
        }
    }

    private static final class BackendTimelineFilter implements GlobalFilter, Ordered {

        @Override
        public int getOrder() {
            return NettyRoutingFilter.ORDER - 1;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Timeline timeline = exchange.getAttribute(TIMELINE);
            if (timeline == null) {
                return chain.filter(exchange);
            }
            return Mono.defer(() -> {
                timeline.backendStarted = System.nanoTime();
                return chain.filter(exchange).doOnSuccess(ignored -> {
                    // NettyRouting completes after headers; NettyWriteResponse (-1) writes the body afterwards.
                    if (exchange.getAttribute(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR) != null) {
                        timeline.backendHeaders = System.nanoTime();
                    } else {
                        timeline.backendFinished = System.nanoTime();
                    }
                })
                        // The outer error handler may write a delayed body after routing has already failed.
                        .doOnError(error -> timeline.backendFinished = System.nanoTime())
                        .doOnCancel(() -> timeline.backendFinished = System.nanoTime());
            });
        }
    }

    /** Decorate the native limiter, retaining its route configuration and fail-closed behavior. */
    private static final class ObservedRateLimiter implements RateLimiter<Bucket4jRateLimiter.Config> {

        private final Bucket4jRateLimiter delegate;
        private final String backend;

        private ObservedRateLimiter(Bucket4jRateLimiter delegate, String backend) {
            this.delegate = delegate;
            this.backend = backend;
        }

        @Override
        public Mono<Response> isAllowed(String routeId, String id) {
            return Mono.deferContextual(context -> {
                Timeline timeline = context.getOrDefault(TIMELINE, null);
                if (timeline == null) {
                    return delegate.isAllowed(routeId, id);
                }
                timeline.rateBackend = backend;
                timeline.rateStarted = System.nanoTime();
                // The native method starts a CompletableFuture immediately, so invoke it inside defer.
                return Mono.defer(() -> delegate.isAllowed(routeId, id))
                        .doOnNext(response -> {
                            timeline.rateFinished = System.nanoTime();
                            // Bucket4j's native fail-closed fallback returns remaining=-1 rather than an error signal.
                            timeline.rateOutcome = response.getHeaders().containsValue("-1") ? "backend_error"
                                    : response.isAllowed() ? "allowed" : "denied";
                        })
                        .doOnError(error -> {
                            timeline.rateFinished = System.nanoTime();
                            timeline.rateOutcome = "error";
                        });
            });
        }

        @Override
        public Class<Bucket4jRateLimiter.Config> getConfigClass() {
            return delegate.getConfigClass();
        }

        @Override
        public Bucket4jRateLimiter.Config newConfig() {
            return delegate.newConfig();
        }

        @Override
        public Map<String, Bucket4jRateLimiter.Config> getConfig() {
            return delegate.getConfig();
        }
    }

    private static final class Timeline {

        private final String requestId;
        private final long started = System.nanoTime();
        private final long slowThresholdNanos;
        private final AtomicBoolean logged = new AtomicBoolean();
        private volatile long rateStarted;
        private volatile long rateFinished;
        private volatile String rateBackend;
        private volatile String rateOutcome;
        private volatile long backendStarted;
        private volatile long backendHeaders;
        private volatile long backendFinished;
        private volatile Throwable error;
        private volatile ServerWebExchange exchange;

        private Timeline(String requestId, long slowThresholdNanos) {
            this.requestId = requestId;
            this.slowThresholdNanos = slowThresholdNanos;
        }

        private void log(ServerHttpRequest request, ServerHttpResponse response, SignalType signal) {
            if (!logged.compareAndSet(false, true)) {
                return;
            }
            long finished = System.nanoTime();
            HttpStatusCode status = response.getStatusCode();
            if (status == null && signal != SignalType.CANCEL) {
                status = HttpStatusCode.valueOf(error == null ? 200 : 500);
            }
            String path = request.getURI().getRawPath();
            if (signal == SignalType.ON_COMPLETE && error == null && status != null && status.value() == 200
                    && ("/actuator/health/liveness".equals(path)
                    || "/actuator/health/readiness".equals(path))) {
                return;
            }
            long total = Math.max(0, finished - started);
            var event = PERFORMANCE.atInfo()
                    .addKeyValue("event.dataset", "http.performance")
                    .addKeyValue("event.kind", "event")
                    .addKeyValue("event.duration", total)
                    .addKeyValue("event.outcome", signal == SignalType.CANCEL ? "unknown"
                            : error != null || status != null && status.isError() ? "failure" : "success")
                    .addKeyValue("http.request.id", requestId)
                    .addKeyValue("http.request.method", request.getMethod().name())
                    .addKeyValue("url.path", path)
                    .addKeyValue("performance.total_ms", millis(total))
                    .addKeyValue("performance.slow", total >= slowThresholdNanos)
                    .addKeyValue("labels.completion_signal", signal.name());
            if (status != null) {
                event.addKeyValue("http.response.status_code", status.value());
            }
            Route route = exchange == null ? null : exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null) {
                event.addKeyValue("labels.gateway_route", route.getId());
            }
            if (rateStarted != 0) {
                event.addKeyValue("performance.rate_limit_ms", millis((rateFinished == 0 ? finished : rateFinished) - rateStarted))
                        .addKeyValue("labels.rate_limit_backend", rateBackend)
                        .addKeyValue("labels.rate_limit_outcome", rateOutcome == null ? "cancelled" : rateOutcome);
            }
            if (backendStarted != 0) {
                event.addKeyValue("performance.gateway_before_backend_ms", millis(backendStarted - started));
                if (backendHeaders != 0) {
                    event.addKeyValue("performance.backend_headers_ms", millis(backendHeaders - backendStarted))
                            .addKeyValue("performance.response_transfer_ms", millis(finished - backendHeaders));
                } else {
                    event.addKeyValue("performance.backend_wait_ms",
                            millis((backendFinished == 0 ? finished : backendFinished) - backendStarted));
                }
            }
            if (error != null) {
                // Exception messages can contain upstream URLs, credentials or client input; the class is enough here.
                event.addKeyValue("error.type", error.getClass().getName());
            }
            event.log("Gateway request timing");
        }

        private static double millis(long nanos) {
            return Math.max(0, nanos) / 1_000_000.0;
        }
    }
}
