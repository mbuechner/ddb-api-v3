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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/** Public reads, verified writers. Missing trust configuration never enables a write bypass. */
@Configuration(proxyBeanMethods = false)
public final class GatewaySecurityConfiguration {

    @Bean
    static SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http,
            ObjectProvider<ReactiveJwtDecoder> decoders, OAuth2ResourceServerProperties properties,
            @Value("${ddb.security.dev-write-token:}") String devToken, Environment environment) {
        final var jwt = properties.getJwt();
        final var decoder = decoders.getIfAvailable();
        final boolean jwtConfigured = decoder != null || StringUtils.hasText(jwt.getIssuerUri())
                || StringUtils.hasText(jwt.getJwkSetUri()) || jwt.getPublicKeyLocation() != null
                || !jwt.getAudiences().isEmpty();
        if (jwtConfigured) {
            Assert.hasText(jwt.getIssuerUri(), "JWT writes require an explicit issuer-uri");
            Assert.isTrue(!jwt.getAudiences().isEmpty()
                            && jwt.getAudiences().stream().allMatch(StringUtils::hasText),
                    "JWT writes require explicit nonblank audiences");
        }

        final var unauthorized = new BearerTokenServerAuthenticationEntryPoint();
        final var forbidden = new BearerTokenServerAccessDeniedHandler();
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .cors(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .authorizeExchange(access -> access
                        .pathMatchers(HttpMethod.GET, "/**").permitAll()
                        .pathMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/3/records/*", "/records/*")
                        .hasAuthority("SCOPE_records:write")
                        .pathMatchers(HttpMethod.PUT, "/3/records/*", "/records/*")
                        .hasAuthority("SCOPE_records:write")
                        .anyExchange().denyAll());
        if (decoder != null) {
            http.oauth2ResourceServer(resource -> resource.authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden).jwt(token -> token.jwtDecoder(decoder)));
        }
        if (!devToken.isEmpty()) {
            Assert.isTrue(environment.acceptsProfiles(Profiles.of("dev & !prod")),
                    "The development write token is allowed only in dev without prod");
            Assert.isTrue(!jwtConfigured, "The development write token cannot be combined with JWT trust");
            final var introspector = developmentToken(devToken);
            http.oauth2ResourceServer(resource -> resource.authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden).opaqueToken(token -> token.introspector(introspector)));
        }
        return http.build();
    }

    /** A local developer secret, not a token issuer: Spring still owns Bearer authentication. */
    private static ReactiveOpaqueTokenIntrospector developmentToken(String token) {
        Assert.isTrue(token.matches("[A-Za-z0-9_-]{43}"),
                "The development write token must encode 32 random bytes as unpadded Base64URL");
        final byte[] decoded = Base64.getUrlDecoder().decode(token);
        Assert.isTrue(decoded.length == 32 && Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(decoded).equals(token),
                "The development write token must use canonical Base64URL");
        final byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        final var principal = new DefaultOAuth2AuthenticatedPrincipal("dev-writer",
                Map.of("client_id", "dev-writer"), List.of(new SimpleGrantedAuthority("SCOPE_records:write")));
        return offered -> MessageDigest.isEqual(expected, offered.getBytes(StandardCharsets.UTF_8))
                ? Mono.just(principal) : Mono.error(new BadOpaqueTokenException("Invalid bearer token"));
    }

    /** Boot combines this with its issuer/audience checks; expiry must never be optional. */
    @Bean
    static OAuth2TokenValidator<Jwt> gatewayTokenExpiry(OAuth2ResourceServerProperties properties) {
        final var validator = new JwtTimestampValidator();
        validator.setAllowEmptyExpiryClaim(false);
        final String principal = properties.getJwt().getPrincipalClaimName();
        return new DelegatingOAuth2TokenValidator<>(validator,
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                new JwtClaimValidator<Object>(StringUtils.hasText(principal) ? principal : JwtClaimNames.SUB,
                        value -> value instanceof String text && StringUtils.hasText(text)));
    }

    /** Reuse the gateway's CORS policy before security can return a 401 or 403. */
    @Bean
    static CorsConfigurationSource gatewaySecurityCors(GlobalCorsProperties properties) {
        final var source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(properties.getCorsConfigurations());
        return source;
    }
}
