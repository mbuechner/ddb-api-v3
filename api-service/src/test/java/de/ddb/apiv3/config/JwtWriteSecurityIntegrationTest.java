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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import de.ddb.apiv3.support.JwtTestIssuer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real signatures and HTTP: denied writes reach neither Jena nor the repository. */
@SpringBootTest(classes = WriteSecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "ddb.api.base-url=https://api.example.test",
                "ddb.security.dev-write-token=",
                "spring.security.oauth2.resourceserver.jwt.principal-claim-name=client_id",
                "management.endpoint.health.group.readiness.include=readinessState"})
class JwtWriteSecurityIntegrationTest {

    private static final String ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PATH = "/3/records/" + ID;
    private static final String RDF = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:ex="https://example.org/" xml:base="/">
              <rdf:Description rdf:about="items/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA">
                <ex:title>Authorized upload</ex:title>
              </rdf:Description>
            </rdf:RDF>
            """;
    private static final JwtTestIssuer ISSUER = issuer();

    @LocalServerPort
    private int port;

    @MockitoBean
    private S3RdfDocumentStore repository;

    @Autowired
    private OAuth2ResourceServerProperties securityProperties;

    @DynamicPropertySource
    static void jwtTrust(DynamicPropertyRegistry properties) {
        properties.add("OAUTH2_ISSUER_URI", ISSUER::issuerUri);
        properties.add("OAUTH2_AUDIENCE", () -> JwtTestIssuer.AUDIENCE);
        properties.add("OAUTH2_JWK_SET_URI", () -> "");
    }

    @BeforeEach
    void successfulRepository() {
        when(repository.create(anyString(), anyString(), any(byte[].class))).thenReturn(Optional.of("\"new-etag\""));
        when(repository.replace(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Optional.of("\"updated-etag\""));
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Test
    void authorizedPostAndPutUseTheActualControllerAndRdfValidator() throws Exception {
        var created = send("POST", PATH, ISSUER.token(), RDF);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Set-Cookie")).isEmpty();
        verify(repository).create(anyString(), anyString(), any(byte[].class));

        var replaced = send("PUT", PATH, ISSUER.token(), RDF);
        assertThat(replaced.statusCode()).isEqualTo(204);
        verify(repository).replace(anyString(), anyString(), any(byte[].class), anyString());
    }

    @Test
    void discoversPublicKeysWhenTheOptionalJwksEnvironmentValueIsEmpty() throws Exception {
        assertThat(securityProperties.getJwt().getJwkSetUri()).isEmpty();
        assertThat(send("POST", PATH, ISSUER.token(), RDF).statusCode()).isEqualTo(201);
        assertThat(ISSUER.discoveryRequests()).isPositive();
        assertThat(ISSUER.keyRequests()).isPositive();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void invalidCredentialsRejectBeforeReadingRdfOrTouchingStorage(String description, String token)
            throws Exception {
        for (String method : new String[] {"POST", "PUT"}) {
            var response = send(method, PATH, token, "This must never reach the RDF parser");
            assertThat(response.statusCode()).as(description + " " + method).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(value ->
                    assertThat(value).startsWith("Bearer"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void aTrustedTokenWithoutWriteScopeIsForbidden() throws Exception {
        var token = ISSUER.token(claims -> claims.claim("scope", "records:read"));
        assertThat(send("POST", PATH, token, RDF).statusCode()).isEqualTo(403);
        assertThat(send("PUT", PATH, token, RDF).statusCode()).isEqualTo(403);
        verifyNoInteractions(repository);
    }

    @Test
    void spoofedIdentityHeadersAndCookiesNeverAuthorizeAWrite() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request(PATH).header("X-Client-Id", "trusted-writer")
                            .header("X-Forwarded-User", "trusted-writer")
                            .header("Cookie", "JSESSIONID=some-other-session")
                            .POST(HttpRequest.BodyPublishers.ofString(RDF)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void readsAndPreflightStayPublicWhileOtherUnsafeMethodsAndAliasesRemainProtected() throws Exception {
        assertThat(send("GET", PATH, null, "").statusCode()).isEqualTo(404);
        assertThat(send("HEAD", PATH, null, "").statusCode()).isEqualTo(404);
        assertThat(send("OPTIONS", PATH, null, "").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/records/" + ID, null, RDF).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/records/" + ID, ISSUER.token(), RDF).statusCode()).isEqualTo(308);
        assertThat(send("DELETE", PATH, ISSUER.token(), "").statusCode()).isEqualTo(403);
    }

    @Test
    void validAuthorizationPreservesRdfValidationAndNeverFallsBackToADevToken() throws Exception {
        assertThat(send("POST", PATH, ISSUER.token(), "not RDF").statusCode()).isEqualTo(422);
        assertThat(send("POST", PATH, "A".repeat(43), RDF).statusCode()).isEqualTo(401);
        verifyNoInteractions(repository);
    }

    private static Stream<Arguments> invalidTokens() throws JOSEException {
        var foreignKey = new RSAKeyGenerator(2048).keyID("writer-test-key").generate();
        var hsToken = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), ISSUER.claims().build());
        hsToken.sign(new MACSigner(new byte[32]));
        return Stream.of(
                Arguments.of("missing token", null),
                Arguments.of("malformed token", "not-a-jwt"),
                Arguments.of("expired token", ISSUER.token(claims ->
                        claims.expirationTime(Date.from(Instant.now().minusSeconds(120))))),
                Arguments.of("future token", ISSUER.token(claims ->
                        claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120))))),
                Arguments.of("wrong issuer", ISSUER.token(claims -> claims.issuer("https://untrusted.example"))),
                Arguments.of("wrong audience", ISSUER.token(claims -> claims.audience("other-api"))),
                Arguments.of("missing expiry", ISSUER.token(claims -> claims.expirationTime(null))),
                Arguments.of("missing subject", ISSUER.token(claims -> claims.subject(null))),
                Arguments.of("missing configured principal", ISSUER.token(claims -> claims.claim("client_id", null))),
                Arguments.of("non-string configured principal", ISSUER.token(claims -> claims.claim("client_id", 42))),
                Arguments.of("wrong signature", JwtTestIssuer.sign(ISSUER.claims().build(), foreignKey)),
                Arguments.of("unsigned token", new PlainJWT(ISSUER.claims().build()).serialize()),
                Arguments.of("symmetric token", hsToken.serialize()));
    }

    private HttpResponse<Void> send(String method, String path, String token, String body) throws Exception {
        var request = request(path).header("Content-Type", "application/rdf+xml")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if ("PUT".equals(method)) {
            request.header("If-Match", "\"old-etag\"");
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private static JwtTestIssuer issuer() {
        try {
            return new JwtTestIssuer();
        } catch (IOException | JOSEException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
