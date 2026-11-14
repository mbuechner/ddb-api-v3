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

package de.ddb.apiv3.config;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Public reads, scoped writes. Spring verifies JWTs before RDF or S3 sees a byte. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public final class WriteSecurityConfiguration {

    @Bean
    static SecurityFilterChain recordSecurity(HttpSecurity http, OAuth2ResourceServerProperties properties,
            Environment environment, @Value("${ddb.security.dev-write-token:}") String devToken)
            throws Exception {
        var jwt = properties.getJwt();
        boolean configured = StringUtils.hasText(jwt.getIssuerUri())
                || StringUtils.hasText(jwt.getJwkSetUri()) || jwt.getPublicKeyLocation() != null
                || !jwt.getAudiences().isEmpty();
        if (!devToken.isEmpty()) {
            Assert.isTrue(environment.acceptsProfiles(Profiles.of("dev & !prod")),
                    "The development write token is allowed only in dev, never prod");
            Assert.isTrue(!configured, "Configure JWT trust or the development write token, never both");
            Assert.isTrue(devToken.matches("[A-Za-z0-9_-]{43}"),
                    "DDB_DEV_WRITE_TOKEN must encode 32 random bytes as unpadded Base64URL (43 characters); see docs/security.md");
            byte[] decoded = Base64.getUrlDecoder().decode(devToken);
            Assert.isTrue(decoded.length == 32 && Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(decoded).equals(devToken),
                    "DDB_DEV_WRITE_TOKEN must be 32 random bytes as canonical unpadded Base64URL");
            byte[] expected = devToken.getBytes(StandardCharsets.UTF_8);
            var principal = new DefaultOAuth2AuthenticatedPrincipal("dev-writer",
                    Map.of("client_id", "dev-writer"), List.of(new SimpleGrantedAuthority("SCOPE_records:write")));
            http.oauth2ResourceServer(resource -> resource.opaqueToken(opaque -> opaque.introspector(token -> {
                if (!MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
                    throw new BadOpaqueTokenException("Invalid bearer token");
                }
                return principal;
            })));
        }
        if (configured) {
            Assert.hasText(jwt.getIssuerUri(), "JWT writes require an issuer-uri");
            Assert.isTrue(!jwt.getAudiences().isEmpty()
                            && jwt.getAudiences().stream().allMatch(StringUtils::hasText),
                    "JWT writes require explicit audiences");
            http.oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
        }
        // Bearer tokens only: no session or cookie authentication, so no CSRF token is involved.
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/3/records/*", "/records/*")
                                .hasAuthority("SCOPE_records:write")
                        .requestMatchers(HttpMethod.PUT, "/3/records/*", "/records/*")
                                .hasAuthority("SCOPE_records:write")
                        .anyRequest().denyAll())
                .build();
    }

    /** Boot adds this native validator to signature, issuer and audience validation. */
    @Bean
    static OAuth2TokenValidator<Jwt> expiringAccessTokens(OAuth2ResourceServerProperties properties) {
        var validator = new JwtTimestampValidator();
        validator.setAllowEmptyExpiryClaim(false);
        String principal = properties.getJwt().getPrincipalClaimName();
        return new DelegatingOAuth2TokenValidator<>(validator,
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                new JwtClaimValidator<Object>(StringUtils.hasText(principal) ? principal : JwtClaimNames.SUB,
                        value -> value instanceof String text && StringUtils.hasText(text)));
    }
}
