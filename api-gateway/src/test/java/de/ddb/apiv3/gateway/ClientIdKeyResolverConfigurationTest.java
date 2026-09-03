/*
 * Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This code was generated with assistance from OpenAI ChatGPT.
 */
package de.ddb.apiv3.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests the trust order used to form rate-limit bucket keys.
 *
 * <p>These tests deliberately avoid a Spring context: they document the security-sensitive
 * precedence of an authenticated principal over development-only fallbacks, including rejection
 * of malformed header values. Forwarded proxy headers are intentionally outside this contract;
 * only the socket peer may identify anonymous traffic.</p>
 */
class ClientIdKeyResolverConfigurationTest {

    private final ClientIdKeyResolverConfiguration configuration =
            new ClientIdKeyResolverConfiguration();

    @Test
    void prefersAuthenticatedPrincipalOverDevelopmentHeader() {
        KeyResolver resolver = configuration.clientIdKeyResolver(true, "X-Client-Id", true);
        ServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/3/records/id")
                                .header("X-Client-Id", "untrusted-header")
                                .build())
                .mutate()
                .principal(Mono.just((Principal) () -> "authenticated-client"))
                .build();

        StepVerifier.create(resolver.resolve(exchange))
                .assertNext(key -> assertThat(key).isEqualTo("client:authenticated-client"))
                .verifyComplete();
    }

    @Test
    void acceptsOnlyValidatedDevelopmentHeaderWhenEnabled() {
        KeyResolver resolver = configuration.clientIdKeyResolver(true, "X-Client-Id", false);
        MockServerWebExchange valid = MockServerWebExchange.from(MockServerHttpRequest.get("/3/records/id")
                .header("X-Client-Id", "client-42")
                .build());
        MockServerWebExchange invalid = MockServerWebExchange.from(MockServerHttpRequest.get("/3/records/id")
                .header("X-Client-Id", "invalid client id")
                .build());

        StepVerifier.create(resolver.resolve(valid)).expectNext("client:client-42").verifyComplete();
        StepVerifier.create(resolver.resolve(invalid)).verifyComplete();
    }

    @Test
    void ignoresHeaderWhenProductionFallbackIsDisabled() {
        KeyResolver resolver = configuration.clientIdKeyResolver(false, "X-Client-Id", false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/3/records/id")
                .header("X-Client-Id", "client-42")
                .build());

        StepVerifier.create(resolver.resolve(exchange)).verifyComplete();
    }

    @Test
    void isolatesAnonymousTrafficByDirectRemoteAddress() {
        KeyResolver resolver = configuration.clientIdKeyResolver(false, "X-Client-Id", true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/3/records/id")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                .build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("anonymous-ip:192.0.2.10")
                .verifyComplete();
    }
}
