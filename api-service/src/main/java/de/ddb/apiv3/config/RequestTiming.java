/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */
package de.ddb.apiv3.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.jetty.server.Request;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;

/** Request-local wall time, not CPU time. The servlet scope never leaks to the next virtual thread. */
public final class RequestTiming {

    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestTiming.class.getName();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final ThreadLocal<RequestTiming> CURRENT = new ThreadLocal<>();
    private static final Stage NOOP = () -> { };
    private final String requestId;
    // Correlation stays active when timing is off; do not allocate a stage map for that fast path.
    private Map<String, Long> stages;
    private boolean enabled;
    private boolean captureConfigured;
    private long admissionStarted;

    private RequestTiming(String candidate) {
        // This is an untrusted correlation label, never an authentication identity.
        requestId = candidate != null && IDENTIFIER.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    public static RequestTiming get(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof RequestTiming timing) {
            return timing;
        }
        var timing = new RequestTiming(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, timing);
        return timing;
    }

    static RequestTiming get(Request request) {
        if (request.getAttribute(ATTRIBUTE) instanceof RequestTiming timing) {
            return timing;
        }
        var timing = new RequestTiming(request.getHeaders().get(HEADER));
        request.setAttribute(ATTRIBUTE, timing);
        return timing;
    }

    public static String requestId(WebRequest request) {
        if (request.getAttribute(ATTRIBUTE, WebRequest.SCOPE_REQUEST) instanceof RequestTiming timing) {
            return timing.requestId;
        }
        var timing = new RequestTiming(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, timing, WebRequest.SCOPE_REQUEST);
        return timing.requestId;
    }

    public static String requestId() {
        var current = CURRENT.get();
        if (current != null) {
            return current.requestId;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return get(attributes.getRequest()).requestId;
        }
        return UUID.randomUUID().toString();
    }

    public String requestIdValue() {
        return requestId;
    }

    void enable(boolean value) {
        // QoS may hand the request to another thread. Keep its admission-time logging decision.
        if (!captureConfigured) {
            enabled = value;
            captureConfigured = true;
        }
    }

    Stage bind() {
        var previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Stage names are source-code constants; nothing from RDF, URLs or headers becomes a field name. */
    public static Stage start(String stage) {
        var timing = CURRENT.get();
        if (timing == null || !timing.enabled) {
            return NOOP;
        }
        long started = System.nanoTime();
        return () -> timing.record(stage, System.nanoTime() - started);
    }

    void admissionStarted(boolean performanceEnabled) {
        enable(performanceEnabled);
        if (performanceEnabled && admissionStarted == 0) {
            admissionStarted = System.nanoTime();
        }
    }

    // QoS entry -> downstream handler includes suspended wait and resumption scheduling, not CPU time.
    void admissionFinished() {
        if (admissionStarted != 0) {
            record("capacity_wait", System.nanoTime() - admissionStarted);
            admissionStarted = 0;
        }
    }

    private synchronized void record(String stage, long nanos) {
        if (stages == null) {
            stages = new LinkedHashMap<>();
        }
        stages.merge(stage, Math.max(0, nanos), Long::sum);
    }

    synchronized void addFields(LoggingEventBuilder event, long totalNanos, long slowThresholdNanos) {
        if (!enabled) {
            return;
        }
        event.addKeyValue("performance.total_ms", totalNanos / 1_000_000.0)
                .addKeyValue("performance.slow", totalNanos >= slowThresholdNanos);
        if (stages != null) {
            stages.forEach((stage, nanos) -> event.addKeyValue("performance." + stage + "_ms", nanos / 1_000_000.0));
        }
    }

    @FunctionalInterface
    public interface Stage extends AutoCloseable {
        @Override
        void close();
    }
}
