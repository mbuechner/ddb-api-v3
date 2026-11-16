/*
 * Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */
package de.ddb.apiv3.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Timings enrich the existing access event; the threshold marks slow requests without hiding fast baselines. */
@Validated
@ConfigurationProperties("ddb.logging.performance")
public record PerformanceLoggingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100ms") @NotNull @DurationMin(millis = 1) Duration slowThreshold) {

    public PerformanceLoggingProperties {
        if (slowThreshold != null) {
            try {
                // Fail while binding configuration, never while Jetty is completing a response.
                slowThreshold.toNanos();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("The slow-request threshold must fit in nanoseconds", exception);
            }
        }
    }
}
