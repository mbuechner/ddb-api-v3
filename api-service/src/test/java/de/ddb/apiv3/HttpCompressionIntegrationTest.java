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
package de.ddb.apiv3;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies request and response compression at the embedded Jetty boundary.
 *
 * <p>MockMvc bypasses the servlet container and cannot establish this behavior, so the tests use
 * a real random-port server and the JDK HTTP client. They protect byte preservation, the
 * representation-specific strong ETag and decompression of a complete gzip multipart entity.
 * Other content codings are not implied by these tests.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:compression-test;DB_CLOSE_DELAY=-1",
    "server.compression.min-response-size=1B"
})
@ActiveProfiles("dev")
class HttpCompressionIntegrationTest {

    private static final String DEMO_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String UPLOAD_ID = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
    private static final String RDF_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="https://example.org/record/compressed-upload">
                <rdf:type rdf:resource="http://www.europeana.eu/schemas/edm/ProvidedCHO"/>
              </rdf:Description>
            </rdf:RDF>
            """;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void compressesResponsesAndKeepsStrongEtagsRepresentationSpecific() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/3/records/" + DEMO_ID + "/content"))
                .header("Accept", "application/rdf+xml")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(response.headers().firstValue("Vary").orElse("")).contains("Accept-Encoding");
        assertThat(response.headers().firstValue("ETag").orElse("")).endsWith("--gzip\"");
        assertThat(gunzip(response.body()))
                .isEqualTo(readIdentityContent(DEMO_ID));
    }

    @Test
    void decompressesTheCompleteGzipMultipartEntityBeforeUploadProcessing() throws Exception {
        String boundary = "gzip-upload-boundary";
        byte[] requestBody = gzip(multipartBody(boundary));
        HttpRequest request = HttpRequest.newBuilder(uri("/3/records"))
                .header("Accept", "application/ld+json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location"))
                .contains("/3/records/" + UPLOAD_ID);
        assertThat(readIdentityContent(UPLOAD_ID))
                .isEqualTo(RDF_XML.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readIdentityContent(String recordId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/3/records/" + recordId + "/content"))
                .header("Accept", "application/rdf+xml")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        return response.body();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static byte[] multipartBody(String boundary) {
        String metadata = """
                {
                  "recordId": "%s",
                  "datasetIds": ["EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"],
                  "providerId": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                  "aggregatorId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "sourceId": "gzip-upload-001",
                  "publicationStatus": "PUBLISHED",
                  "profileVersion": "ddb-v3"
                }
                """.formatted(UPLOAD_ID);
        String body = "--%1$s\r\n"
                + "Content-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + metadata + "\r\n"
                + "--%1$s\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"record.rdf\"\r\n"
                + "Content-Type: application/rdf+xml\r\n\r\n"
                + RDF_XML + "\r\n"
                + "--%1$s--\r\n";
        return body.formatted(boundary).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        }
    }
}
