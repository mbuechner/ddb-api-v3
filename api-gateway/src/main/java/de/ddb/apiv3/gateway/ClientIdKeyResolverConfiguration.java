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

import java.security.Principal;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** Resolves one validated, low-cardinality rate-limit bucket key per API client. */
@Configuration(proxyBeanMethods = false)
public class ClientIdKeyResolverConfiguration {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /**
     * Uses the authenticated principal whenever an upstream security filter has established one.
     * A syntactically validated header fallback exists for development and integration testing;
     * production disables it so an untrusted caller cannot select another client's bucket.
     * Anonymous traffic can use the direct peer address in a separate namespace; forwarded
     * address headers are intentionally not trusted or parsed here.
     *
     * @param allowHeader whether the development-only client header may be used
     * @param headerName centrally configured client header name
     * @param allowRemoteAddress whether anonymous requests may use the direct peer address
     * @return a resolver producing keys in the {@code client:<client-id>} namespace
     */
    @Bean
    KeyResolver clientIdKeyResolver(
            @Value("${ddb.gateway.client-id.allow-header:false}") boolean allowHeader,
            @Value("${ddb.gateway.client-id.header-name:X-Client-Id}") String headerName,
            @Value("${ddb.gateway.client-id.allow-remote-address:true}")
                    boolean allowRemoteAddress) {
        return exchange -> exchange.<Principal>getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.defer(() -> allowHeader
                        ? Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(headerName))
                        : Mono.empty()))
                .filter(CLIENT_ID.asMatchPredicate())
                .map(clientId -> "client:" + clientId)
                .switchIfEmpty(Mono.defer(() -> allowRemoteAddress
                        ? Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                                .flatMap(address -> Mono.justOrEmpty(address.getAddress()))
                                .map(address -> address.getHostAddress())
                                .map(ip -> "anonymous-ip:" + ip)
                        : Mono.empty()));
    }
}
