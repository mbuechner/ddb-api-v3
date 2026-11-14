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

package de.ddb.apiv3.gateway;

import java.security.Principal;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/** Resolves one validated, low-cardinality rate-limit bucket key per API client. */
@Configuration(proxyBeanMethods = false)
public final class ClientIdKeyResolverConfiguration {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Principal first, optional dev header second, server-resolved IP last. Proxies require trust. */
    @Bean
    static KeyResolver clientIdKeyResolver(
            @Value("${ddb.gateway.client-id.allow-header:false}") boolean allowHeader,
            @Value("${ddb.gateway.client-id.header-name:X-Client-Id}") String headerName,
            @Value("${ddb.gateway.client-id.allow-remote-address:true}")
                    boolean allowRemoteAddress) {
        return exchange -> exchange.<Principal>getPrincipal()
                .flatMap(principal -> Mono.justOrEmpty(principal.getName()))
                .filter(StringUtils::hasText)
                .map(clientId -> "client:" + clientId)
                .switchIfEmpty(Mono.defer(() -> allowHeader
                        ? Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(headerName))
                                .filter(CLIENT_ID.asMatchPredicate()).map(clientId -> "client:" + clientId)
                        : Mono.empty()))
                .switchIfEmpty(Mono.defer(() -> allowRemoteAddress
                        ? Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                                .flatMap(address -> Mono.justOrEmpty(address.getAddress()))
                                .map(address -> address.getHostAddress())
                                .map(ip -> "anonymous-ip:" + ip)
                        : Mono.empty()));
    }
}
