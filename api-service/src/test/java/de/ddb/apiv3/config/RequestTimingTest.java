/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */
package de.ddb.apiv3.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTimingTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unsafe label", "bad/id", "\n",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void replacesInvalidCorrelationLabels(String candidate) {
        var request = new MockHttpServletRequest();
        if (candidate != null) {
            request.addHeader(RequestTiming.HEADER, candidate);
        }
        var timing = RequestTiming.get(request);
        assertThat(timing.requestIdValue()).matches("[0-9a-f-]{36}");
        assertThat(RequestTiming.get(request)).isSameAs(timing);
    }

    @Test
    void restoresContextAfterFailuresAndDoesNotMeasureDisabledRequests() {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestTiming.HEADER, "diagnostic:safe-id");
        var timing = RequestTiming.get(request);
        timing.enable(true);
        assertThatThrownBy(() -> {
            try (var scope = timing.bind(); var stage = RequestTiming.start("failed_stage")) {
                assertThat(RequestTiming.requestId()).isEqualTo("diagnostic:safe-id");
                throw new IllegalStateException("test failure");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(RequestTiming.requestId()).isNotEqualTo("diagnostic:safe-id");
        assertThat(fields(timing)).containsKey("performance.failed_stage_ms")
                .containsEntry("performance.total_ms", 10.0).containsEntry("performance.slow", true);

        var disabled = RequestTiming.get(new MockHttpServletRequest());
        disabled.enable(false);
        try (var scope = disabled.bind(); var stage = RequestTiming.start("disabled_stage")) {
            assertThat(RequestTiming.requestId()).isEqualTo(disabled.requestIdValue());
        }
        assertThat(fields(disabled)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,true,INFO", "true,false,INFO", "true,true,WARN"})
    void doesNotCaptureWhenPerformanceOrAccessOutputIsDisabled(
            boolean performanceEnabled, boolean accessEnabled, String level) throws Exception {
        var access = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
        var previousLevel = access.getLevel();
        access.setLevel(Level.toLevel(level));
        try {
            var request = new MockHttpServletRequest();
            request.addHeader(RequestTiming.HEADER, "diagnostic:disabled-output");
            var response = new MockHttpServletResponse();
            var filter = HttpAccessLogConfiguration.requestTimingFilter(
                    new PerformanceLoggingProperties(performanceEnabled, Duration.ofMillis(100)), accessEnabled);
            filter.getFilter().doFilter(request, response, (incoming, outgoing) -> {
                try (var stage = RequestTiming.start("unused_stage")) {
                    assertThat(RequestTiming.requestId()).isEqualTo("diagnostic:disabled-output");
                }
            });
            assertThat(response.getHeader(RequestTiming.HEADER)).isEqualTo("diagnostic:disabled-output");
            assertThat(fields(RequestTiming.get(request))).isEmpty();
        } finally {
            access.setLevel(previousLevel);
        }
    }

    @Test
    void keepsTheCaptureDecisionForTheEntireRequest() {
        var enabled = RequestTiming.get(new MockHttpServletRequest());
        enabled.enable(true);
        enabled.enable(false);
        try (var scope = enabled.bind(); var stage = RequestTiming.start("retained_stage")) {
            // A later servlet dispatch must not revise the decision made at QoS admission.
        }
        assertThat(fields(enabled)).containsKey("performance.retained_stage_ms");

        var disabled = RequestTiming.get(new MockHttpServletRequest());
        disabled.enable(false);
        disabled.enable(true);
        try (var scope = disabled.bind(); var stage = RequestTiming.start("discarded_stage")) {
            // A logging-level change during a queued request must not create a partial timeline.
        }
        assertThat(fields(disabled)).isEmpty();
    }

    @Test
    void validatesThresholdAndBindsTheDefault() {
        var context = new ApplicationContextRunner().withUserConfiguration(Properties.class);
        context.run(application -> assertThat(application.getBean(PerformanceLoggingProperties.class).slowThreshold())
                .isEqualTo(Duration.ofMillis(100)));
        for (String threshold : new String[] {"0ms", "-1ms", "1ns", "9223372036855ms"}) {
            context.withPropertyValues("ddb.logging.performance.slow-threshold=" + threshold)
                    .run(application -> assertThat(application).hasFailed());
        }
    }

    private static Map<String, Object> fields(RequestTiming timing) {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTimingTest.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var builder = logger.atInfo();
            timing.addFields(builder, 10_000_000, 10_000_000);
            builder.log("test");
            var fields = new HashMap<String, Object>();
            var pairs = events.list.getFirst().getKeyValuePairs();
            if (pairs != null) {
                pairs.forEach(pair -> fields.put(pair.key, pair.value));
            }
            return fields;
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PerformanceLoggingProperties.class)
    static class Properties {
    }
}
