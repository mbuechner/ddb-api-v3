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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

/** Real signatures, JWKS and sockets: an unverified request must never reach the backend. */
class GatewayWriteSecurityIntegrationTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "ddb-test-api";
    private static final String DEV_TOKEN = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);
    private static final AtomicInteger WRITES = new AtomicInteger();
    private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicInteger DISCOVERY_REQUESTS = new AtomicInteger();

    private static RSAKey key;
    private static RSAKey wrongKey;
    private static HttpServer backend;
    private static HttpServer keys;
    private static ExecutorService executor;
    private static HttpClient client;
    private static ConfigurableApplicationContext gateway;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("writer-key").generate();
        wrongKey = new RSAKeyGenerator(2048).keyID("writer-key").generate();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.setExecutor(executor);
        backend.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = 200;
            if (exchange.getRequestMethod().equals("POST") || exchange.getRequestMethod().equals("PUT")) {
                WRITES.incrementAndGet();
                status = exchange.getRequestMethod().equals("POST") ? 201 : 204;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        backend.start();
        keys = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keys.setExecutor(executor);
        keys.createContext("/.well-known/openid-configuration", exchange -> {
            DISCOVERY_REQUESTS.incrementAndGet();
            String issuer = "http://127.0.0.1:" + keys.getAddress().getPort();
            byte[] metadata = ("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                    + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        keys.createContext("/jwks", exchange -> {
            byte[] json = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        keys.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        gateway = start("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
                        + keys.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopInfrastructure() {
        if (gateway != null) gateway.close();
        if (client != null) client.close();
        if (keys != null) keys.stop(0);
        if (backend != null) backend.stop(0);
        if (executor != null) executor.close();
    }

    @Test
    void acceptsOnlySignedScopedWritersAndForwardsTheirTokenUnchanged() throws Exception {
        int before = WRITES.get();
        String token = sign(claims().claim("scope", "records:write").build(), key);
        assertThat(send(gateway, "POST", "/3/records/example", token).statusCode()).isEqualTo(201);
        assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
        assertThat(send(gateway, "PUT", "/3/records/example", token).statusCode()).isEqualTo(204);
        assertThat(WRITES.get()).isEqualTo(before + 2);
        assertThat(gateway.getBeansOfType(MapReactiveUserDetailsService.class)).isEmpty();
    }

    @Test
    void rejectsMissingMalformedExpiredAndUntrustedTokensBeforeRouting() throws Exception {
        int before = WRITES.get();
        var invalid = new ArrayList<String>();
        invalid.add(null);
        invalid.add("not-a-jwt");
        invalid.add(new PlainJWT(claims().claim("scope", "records:write").build()).serialize());
        var hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                claims().claim("scope", "records:write").build());
        hmac.sign(new MACSigner(new byte[32]));
        invalid.add(hmac.serialize());
        invalid.add(sign(claims().claim("scope", "records:write").build(), wrongKey));
        invalid.add(sign(claims().issuer("https://another-issuer.test").claim("scope", "records:write").build(), key));
        invalid.add(sign(claims().audience("another-api").claim("scope", "records:write").build(), key));
        invalid.add(sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(180)))
                .claim("scope", "records:write").build(), key));
        invalid.add(sign(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(180)))
                .claim("scope", "records:write").build(), key));
        invalid.add(sign(claims().expirationTime(null).claim("scope", "records:write").build(), key));
        invalid.add(sign(claims().subject(null).claim("scope", "records:write").build(), key));
        for (String token : invalid) {
            var response = send(gateway, "POST", "/3/records/denied", token);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        }
        assertThat(WRITES.get()).isEqualTo(before);
    }

    @Test
    void itemWritesRequireTheirOwnScopeOnCanonicalAndVersionlessPaths() throws Exception {
        int before = WRITES.get();
        String records = sign(claims().claim("scope", "records:write").build(), key);
        String items = sign(claims().claim("scope", "items:write").build(), key);
        for (String method : new String[] {"POST", "PUT"}) {
            int success = "POST".equals(method) ? 201 : 204;
            for (String path : new String[] {"/3/items/example", "/items/example"}) {
                assertThat(send(gateway, method, path, items).statusCode()).isEqualTo(success);
                assertThat(send(gateway, method, path, records).statusCode()).isEqualTo(403);
                assertThat(send(gateway, method, path, null).statusCode()).isEqualTo(401);
            }
            for (String path : new String[] {"/3/records/example", "/records/example"}) {
                assertThat(send(gateway, method, path, items).statusCode()).isEqualTo(403);
            }
        }
        // The fake backend has no redirects: all four authorized writes must reach it.
        assertThat(WRITES.get()).isEqualTo(before + 4);
    }

    @Test
    void forbidsInsufficientScopeAndUnexpectedUnsafeMethods() throws Exception {
        int before = WRITES.get();
        String read = sign(claims().claim("scope", "records:read").build(), key);
        String write = sign(claims().claim("scope", "records:write").build(), key);
        assertThat(send(gateway, "POST", "/3/records/denied", read).statusCode()).isEqualTo(403);
        assertThat(send(gateway, "PUT", "/records/denied", read).statusCode()).isEqualTo(403);
        assertThat(send(gateway, "DELETE", "/3/records/denied", write).statusCode()).isEqualTo(403);
        assertThat(send(gateway, "PATCH", "/3/records/denied", write).statusCode()).isEqualTo(403);
        assertThat(send(gateway, "POST", "/3/items/denied", write).statusCode()).isEqualTo(403);
        assertThat(WRITES.get()).isEqualTo(before);
    }

    @Test
    void keepsPublicReadsAndCorsWhileRejectingQueryTokensAndAnonymousWrites() throws Exception {
        int before = WRITES.get();
        assertThat(send(gateway, "GET", "/3/records/public", null).statusCode()).isEqualTo(200);
        assertThat(send(gateway, "GET", "/3/items/public", null).statusCode()).isEqualTo(200);
        String write = sign(claims().claim("scope", "records:write").build(), key);
        assertThat(send(gateway, "POST", "/3/records/denied?access_token=" + write, null).statusCode()).isEqualTo(401);
        var preflight = client.send(HttpRequest.newBuilder(uri(gateway, "/3/records/public"))
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        var denied = client.send(HttpRequest.newBuilder(uri(gateway, "/3/records/denied"))
                        .header("Origin", "https://client.example").header("X-Client-Id", "claimed-writer")
                        .POST(HttpRequest.BodyPublishers.ofString("rdf")).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        assertThat(WRITES.get()).isEqualTo(before);
    }

    @Test
    void missingTrustLocksWritesEvenThroughVersionlessAliasesButLeavesReadsAvailable() throws Exception {
        try (var locked = start()) {
            int before = WRITES.get();
            assertThat(send(locked, "GET", "/3/records/public", null).statusCode()).isEqualTo(200);
            assertThat(send(locked, "POST", "/3/records/denied", null).statusCode()).isEqualTo(401);
            assertThat(send(locked, "PUT", "/records/denied", null).statusCode()).isEqualTo(401);
            assertThat(locked.getBeansOfType(MapReactiveUserDetailsService.class)).isEmpty();
            assertThat(WRITES.get()).isEqualTo(before);
        }
    }

    @Test
    void developmentTokenWorksOnlyForTheExplicitLocalSecret() throws Exception {
        try (var local = start("--ddb.security.dev-write-token=" + DEV_TOKEN)) {
            int before = WRITES.get();
            assertThat(send(local, "GET", "/3/records/public", null).statusCode()).isEqualTo(200);
            assertThat(send(local, "POST", "/3/records/local", DEV_TOKEN).statusCode()).isEqualTo(201);
            assertThat(send(local, "PUT", "/3/records/local", DEV_TOKEN).statusCode()).isEqualTo(204);
            assertThat(send(local, "POST", "/3/items/local", DEV_TOKEN).statusCode()).isEqualTo(201);
            assertThat(send(local, "PUT", "/3/items/local", DEV_TOKEN).statusCode()).isEqualTo(204);
            assertThat(send(local, "POST", "/3/records/denied", null).statusCode()).isEqualTo(401);
            assertThat(send(local, "PUT", "/records/denied", "wrong-token").statusCode()).isEqualTo(401);
            assertThat(WRITES.get()).isEqualTo(before + 4);
        }
    }

    @Test
    void discoversPublicKeysWhenTheOptionalJwksEnvironmentValueIsEmpty() throws Exception {
        String issuer = "http://127.0.0.1:" + keys.getAddress().getPort();
        try (var discovered = start("--OAUTH2_ISSUER_URI=" + issuer, "--OAUTH2_AUDIENCE=" + AUDIENCE,
                "--OAUTH2_JWK_SET_URI=")) {
            var properties = discovered.getBean(OAuth2ResourceServerProperties.class);
            assertThat(properties.getJwt().getJwkSetUri()).isEmpty();
            int before = DISCOVERY_REQUESTS.get();
            String valid = sign(claims().issuer(issuer).claim("scope", "records:write").build(), key);
            assertThat(send(discovered, "POST", "/3/records/discovery", valid).statusCode()).isEqualTo(201);
            assertThat(DISCOVERY_REQUESTS.get()).isGreaterThan(before);
            String expired = sign(claims().issuer(issuer)
                    .expirationTime(Date.from(Instant.now().minusSeconds(180)))
                    .claim("scope", "records:write").build(), key);
            assertThat(send(discovered, "POST", "/3/records/discovery", expired).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void rejectsPartialTrustMixedTrustAndNoncanonicalDevelopmentSecrets() {
        assertStartupFails("JWT writes require explicit nonblank audiences",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER);
        assertStartupFails("JWT writes require an explicit issuer-uri",
                "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE);
        assertStartupFails("cannot be combined with JWT trust", "--ddb.security.dev-write-token=" + DEV_TOKEN,
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE);
        assertStartupFails("must encode 32 random bytes", "--ddb.security.dev-write-token=too-short");
        assertStartupFails("must use canonical Base64URL", "--ddb.security.dev-write-token=" + "A".repeat(42) + "B");
        assertStartupFails("allowed only in dev without prod", "--spring.profiles.active=prod",
                "--ddb.security.dev-write-token=" + DEV_TOKEN, "--GATEWAY_REDIS_PASSWORD=public-test-password");
        assertStartupFails("allowed only in dev without prod", "--spring.profiles.active=dev,prod",
                "--ddb.security.dev-write-token=" + DEV_TOKEN, "--GATEWAY_REDIS_PASSWORD=public-test-password");
    }

    @Test
    void requiresTheConfiguredPrincipalClaimBeforeUsingTheClientQuota() throws Exception {
        try (var identified = start("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
                        + keys.getAddress().getPort() + "/jwks",
                "--spring.security.oauth2.resourceserver.jwt.principal-claim-name=client_id")) {
            int before = WRITES.get();
            String missing = sign(claims().claim("scope", "records:write").build(), key);
            assertThat(send(identified, "POST", "/3/records/identified", missing).statusCode()).isEqualTo(401);
            String invalid = sign(claims().claim("scope", "records:write").claim("client_id", 42).build(), key);
            assertThat(send(identified, "POST", "/3/records/identified", invalid).statusCode()).isEqualTo(401);
            assertThat(WRITES.get()).isEqualTo(before);
            String valid = sign(claims().claim("scope", "records:write").claim("client_id", "writer-app").build(), key);
            assertThat(send(identified, "POST", "/3/records/identified", valid).statusCode()).isEqualTo(201);
        }
    }

    private static void assertStartupFails(String reason, String... options) {
        assertThatThrownBy(() -> {
            try (var unexpected = start(options)) {
                throw new AssertionError("Invalid security configuration started: " + unexpected.getId());
            }
        }).hasStackTraceContaining(reason);
    }

    private static ConfigurableApplicationContext start(String... options) {
        var args = new ArrayList<>(List.of("--APP_PROFILE=dev", "--GATEWAY_PORT=0",
                "--GATEWAY_API_SERVICE_URI=http://127.0.0.1:" + backend.getAddress().getPort(),
                "--GATEWAY_CLIENT_ID_HEADER_ENABLED=false", "--GATEWAY_ANONYMOUS_RATE_LIMIT_BY_REMOTE_ADDRESS=true",
                "--GATEWAY_RATE_LIMIT_BURST_CAPACITY=1000", "--GATEWAY_RATE_LIMIT_REPLENISH_RATE=1000",
                "--ddb.security.dev-write-token=", "--management.endpoints.web.exposure.include=health"));
        // Specific test options replace defaults instead of duplicating command-line properties.
        for (String option : options) {
            String key = option.substring(0, option.indexOf('='));
            args.removeIf(value -> value.startsWith(key + "="));
            args.add(option);
        }
        return new SpringApplicationBuilder(DdbApiGatewayApplication.class, IsolatedResources.class)
                .web(WebApplicationType.REACTIVE).run(args.toArray(String[]::new));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedResources {

        @Bean
        static ReactorResourceFactory reactorResourceFactory() {
            final var resources = new ReactorResourceFactory();
            resources.setUseGlobalResources(false);
            return resources;
        }
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("trusted-writer")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static HttpResponse<Void> send(ConfigurableApplicationContext context,
            String method, String path, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri(context, path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static URI uri(ConfigurableApplicationContext context, String path) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
