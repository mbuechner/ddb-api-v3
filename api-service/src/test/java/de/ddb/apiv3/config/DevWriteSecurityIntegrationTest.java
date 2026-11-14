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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Public fixture token only: no production key, identity provider or S3 access. */
@SpringBootTest(classes = WriteSecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.profiles.active=dev",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=",
                "spring.security.oauth2.resourceserver.jwt.audiences=",
                "ddb.api.base-url=https://api.example.test",
                "ddb.security.dev-write-token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "management.endpoint.health.group.readiness.include=readinessState"})
class DevWriteSecurityIntegrationTest {

    private static final String TOKEN = "A".repeat(43);
    private static final String PATH = "/3/records/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String RDF = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:ex="https://example.org/">
              <rdf:Description rdf:about="https://example.org/item">
                <ex:title>Authorized dev upload</ex:title>
              </rdf:Description>
            </rdf:RDF>
            """;

    @LocalServerPort
    private int port;

    @MockitoBean
    private S3RdfDocumentStore repository;

    @BeforeEach
    void successfulRepository() {
        when(repository.create(anyString(), anyString(), any(byte[].class))).thenReturn(Optional.of("\"created\""));
        when(repository.replace(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Optional.of("\"updated\""));
    }

    @Test
    void correctDevBearerTokenAuthorizesBothWriteMethodsWithoutAWebSession() throws Exception {
        assertThat(send("POST", TOKEN, RDF).statusCode()).isEqualTo(201);
        var updated = send("PUT", TOKEN, RDF);
        assertThat(updated.statusCode()).isEqualTo(204);
        assertThat(updated.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void missingOrWrongTokensNeverReachRdfValidationOrStorage() throws Exception {
        for (String method : new String[] {"POST", "PUT"}) {
            assertThat(send(method, null, "not RDF").statusCode()).isEqualTo(401);
            assertThat(send(method, "B" + TOKEN.substring(1), "not RDF").statusCode()).isEqualTo(401);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void validDevAuthenticationDoesNotSkipRdfValidation() throws Exception {
        assertThat(send("POST", TOKEN, "not RDF").statusCode()).isEqualTo(422);
        verifyNoInteractions(repository);
    }

    @Test
    void aTokenInTheQueryOrAClientIdentityHeaderCannotAuthorizeWrites() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(uri(PATH + "?access_token=" + TOKEN))
                            .header("X-Client-Id", "dev-writer").header("Content-Type", "application/rdf+xml")
                            .POST(HttpRequest.BodyPublishers.ofString(RDF)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(401);
        }
        verifyNoInteractions(repository);
    }

    private HttpResponse<Void> send(String method, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(PATH)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/rdf+xml")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
