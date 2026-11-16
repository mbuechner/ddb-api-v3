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

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Checks the shipped YAML against OS-style variables without starting servers or reading .env. */
class GatewayEnvironmentConfigurationTest {

    private static final String GATEWAY = "spring.cloud.gateway.server.webflux.";

    @Test
    void gatewaySettingsStayIndependentFromApiSettings() throws IOException {
        var environment = environment(Map.of(
                "DDB_API_PORT", "18080", "GATEWAY_PORT", "18081",
                "DDB_API_FORWARD_HEADERS_STRATEGY", "framework", "GATEWAY_FORWARD_HEADERS_STRATEGY", "native",
                "DDB_API_ACCESS_LOG_ENABLED", "true", "GATEWAY_ACCESS_LOG_ENABLED", "false",
                "DDB_API_ACCESS_LOG_LEVEL", "ERROR", "GATEWAY_ACCESS_LOG_LEVEL", "INFO",
                "DDB_API_CLIENT_ID_HEADER_NAME", "X-Api-Client", "GATEWAY_CLIENT_ID_HEADER_NAME", "X-Gateway-Client"),
                false);

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18081);
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(environment.getProperty("ddb.logging.access.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("logging.level.de.ddb.apiv3.access")).isEqualTo("INFO");
        assertThat(environment.getProperty("ddb.gateway.client-id.header-name")).isEqualTo("X-Gateway-Client");
        assertRoutes(environment, "http://127.0.0.1:18080");
    }

    @Test
    void explicitGatewayBackendOverridesTheLocalApiPort() throws IOException {
        var environment = environment(Map.of("DDB_API_PORT", "18080",
                "GATEWAY_API_SERVICE_URI", "https://api.internal.example:9443"), false);

        assertRoutes(environment, "https://api.internal.example:9443");
    }

    @Test
    void performanceLoggingUsesGatewaySettingsAndDisablesPoolMetricsWithTimings() throws IOException {
        var environment = environment(Map.of("GATEWAY_PERFORMANCE_LOG_ENABLED", "false",
                "GATEWAY_SLOW_REQUEST_THRESHOLD", "175ms", "DDB_API_PERFORMANCE_LOG_ENABLED", "true",
                "DDB_API_SLOW_REQUEST_THRESHOLD", "900ms"), false);

        assertThat(environment.getProperty("ddb.logging.performance.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("ddb.logging.performance.slow-threshold")).isEqualTo("175ms");
        assertThat(environment.getProperty("logging.level.de.ddb.apiv3.performance")).isEqualTo("INFO");
        assertThat(environment.getProperty(GATEWAY + "httpclient.pool.metrics", Boolean.class)).isFalse();
        assertThat(environment.getProperty(GATEWAY + "routes[0].filters[1].args.rate-limiter"))
                .isEqualTo("#{@observedBucket4jRateLimiter}");
    }

    @Test
    void poolMetricsAreEnabledWithPerformanceLoggingByDefault() throws IOException {
        var environment = environment(Map.of(), false);
        assertThat(environment.getProperty("ddb.logging.performance.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty(GATEWAY + "httpclient.pool.metrics", Boolean.class)).isTrue();
    }

    @Test
    void productionRedisAndRateLimitsUseTheGatewayNamespaceWithoutEnablingSentinel() throws IOException {
        var environment = environment(Map.ofEntries(
                Map.entry("GATEWAY_REDIS_HOST", "redis.internal.example"),
                Map.entry("GATEWAY_REDIS_PORT", "6380"),
                Map.entry("GATEWAY_REDIS_USERNAME", "gateway"),
                Map.entry("GATEWAY_REDIS_PASSWORD", "test-only-password"),
                Map.entry("GATEWAY_REDIS_TLS_ENABLED", "false"),
                Map.entry("GATEWAY_REDIS_CONNECT_TIMEOUT", "250ms"),
                Map.entry("GATEWAY_REDIS_COMMAND_TIMEOUT", "750ms"),
                Map.entry("GATEWAY_RATE_LIMIT_BURST_CAPACITY", "77"),
                Map.entry("GATEWAY_RATE_LIMIT_REPLENISH_RATE", "13"),
                Map.entry("GATEWAY_RATE_LIMIT_REFILL_PERIOD", "2s"),
                Map.entry("GATEWAY_RATE_LIMIT_REQUESTED_TOKENS", "3")), true);

        assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis.internal.example");
        assertThat(environment.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(6380);
        assertThat(environment.getProperty("spring.data.redis.username")).isEqualTo("gateway");
        assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("test-only-password");
        assertThat(environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.data.redis.connect-timeout")).isEqualTo("250ms");
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("750ms");
        assertThat(environment.getProperty("spring.data.redis.sentinel.master")).isNull();
        assertThat(environment.getProperty("ddb.gateway.client-id.allow-header", Boolean.class)).isFalse();
        String limiter = GATEWAY + "routes[0].filters[1].args.bucket4j-rate-limiter.";
        assertThat(environment.getProperty(limiter + "capacity", Integer.class)).isEqualTo(77);
        assertThat(environment.getProperty(limiter + "refillTokens", Integer.class)).isEqualTo(13);
        assertThat(environment.getProperty(limiter + "refillPeriod")).isEqualTo("2s");
        assertThat(environment.getProperty(limiter + "requestedTokens", Integer.class)).isEqualTo(3);
        assertThat(environment.getProperty(GATEWAY + "routes[0].filters[2].args.value")).isEqualTo("13");
        assertThat(environment.getProperty(GATEWAY + "routes[0].filters[3].args.value")).isEqualTo("77");
        assertThat(environment.getProperty(GATEWAY + "routes[0].filters[4].args.value")).isEqualTo("3");
    }

    @Test
    void explicitlyConfiguredNativeSentinelSettingsRemainAvailable() throws IOException {
        var environment = environment(Map.of("GATEWAY_REDIS_PASSWORD", "test-only-password",
                "SPRING_DATA_REDIS_SENTINEL_MASTER", "ddb-api-v3",
                "SPRING_DATA_REDIS_SENTINEL_NODES", "sentinel.internal.example:26379"), true);

        assertThat(environment.getProperty("spring.data.redis.sentinel.master")).isEqualTo("ddb-api-v3");
        assertThat(environment.getProperty("spring.data.redis.sentinel.nodes"))
                .isEqualTo("sentinel.internal.example:26379");
    }

    private static void assertRoutes(StandardEnvironment environment, String expectedUri) {
        for (int route = 0; route < 3; route++) {
            assertThat(environment.getProperty(GATEWAY + "routes[" + route + "].uri")).isEqualTo(expectedUri);
        }
    }

    private static StandardEnvironment environment(Map<String, Object> variables, boolean production)
            throws IOException {
        var environment = new StandardEnvironment();
        // Isolate ambient JVM/OS values; SystemEnvironmentPropertySource retains native ENV-name lookup.
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        var loader = new YamlPropertySourceLoader();
        if (production) {
            loader.load("gateway-production", new ClassPathResource("application-prod.yaml")).forEach(sources::addLast);
        }
        loader.load("gateway", new ClassPathResource("application.yaml")).forEach(sources::addLast);
        return environment;
    }
}
