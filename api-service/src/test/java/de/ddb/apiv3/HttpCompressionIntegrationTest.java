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

import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestServer;
import de.ddb.apiv3.support.S3TestSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Tests gzip at the real Jetty boundary, including the decompressed raw RDF upload limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.compression.min-response-size=1B",
    "ddb.api.max-document-size=1KB"
})
@ActiveProfiles("dev")
class HttpCompressionIntegrationTest extends S3TestSupport {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private RdfCodec codec;

    @Test
    void decompressesGzipRdfBeforeValidationAndStoresUncompressedJelly() throws Exception {
        String id = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri("/3/records/" + id))
                .header("Content-Type", "application/rdf+xml")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(S3TestServer.DEMO_RDF)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(response.headers().firstValue("Location")).contains("/3/records/" + id);
        assertThat(STORAGE.storedContentType(id + "/record")).isEqualTo(RdfCodec.JELLY_CONTENT_TYPE);
        assertRdfXml(codec.fromJelly(STORAGE.storedRdf(id), Lang.RDFXML));
    }

    @Test
    void compressesNegotiatedRdfWithoutChangingTheStrongStorageValidator() throws Exception {
        String id = "CK".repeat(16);
        HttpResponse<byte[]> created = client.send(HttpRequest.newBuilder(uri("/3/records/" + id))
                .header("Content-Type", "application/rdf+xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(S3TestServer.DEMO_RDF))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(created.statusCode()).isEqualTo(201);
        String storageEtag = created.headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri("/3/records/" + id))
                .header("Accept", "application/rdf+xml")
                .header("Accept-Encoding", "gzip")
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/rdf+xml");
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(response.headers().firstValue("ETag").orElse("")).startsWith("W/\"");
        assertThat(response.headers().firstValue("X-Record-ETag")).contains(storageEtag);
        assertThat(String.join(",", response.headers().allValues("Vary"))).contains("Accept", "Accept-Encoding");
        assertRdfXml(gunzip(response.body()));

        String compressedEtag = response.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> unchanged = client.send(HttpRequest.newBuilder(uri("/3/records/" + id))
                .header("Accept", "application/rdf+xml")
                .header("Accept-Encoding", "gzip")
                .header("If-None-Match", compressedEtag)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(unchanged.statusCode()).isEqualTo(304);
        assertThat(unchanged.body()).isEmpty();
        assertThat(unchanged.headers().firstValue("X-Record-ETag")).contains(storageEtag);
    }

    @Test
    void appliesTheUploadLimitToDecompressedBytes() throws Exception {
        String id = "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY";
        byte[] oversized = ("<!--" + "x".repeat(2048) + "-->\n"
                + new String(S3TestServer.DEMO_RDF, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        int writesBefore = STORAGE.writeCount();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri("/3/records/" + id))
                .header("Content-Type", "application/rdf+xml")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(oversized)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(STORAGE.writeCount()).isEqualTo(writesBefore);
    }

    @Test
    void returnsTheRdfValidationProblemWhenGzipIsAccepted() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                        uri("/3/records/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"))
                .header("Content-Type", "application/rdf+xml")
                .header("Accept", "application/problem+json")
                .header("Accept-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofString("<rdf:RDF>"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        // Accept-Encoding: gzip also permits identity; the container may leave errors uncompressed.
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        assertThat(encoding).isIn("gzip", "identity");
        byte[] problem = "gzip".equals(encoding) ? gunzip(response.body()) : response.body();
        if ("gzip".equals(encoding)) {
            assertThat(response.headers().firstValue("Vary").orElse("")).contains("Accept-Encoding");
        }
        assertThat(new String(problem, StandardCharsets.UTF_8)).contains("INVALID_RDF");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void assertRdfXml(byte[] bytes) {
        var actual = ModelFactory.createDefaultModel();
        var expected = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(actual, new ByteArrayInputStream(bytes), Lang.RDFXML);
            RDFDataMgr.read(expected, new ByteArrayInputStream(S3TestServer.DEMO_RDF), Lang.RDFXML);
            assertThat(actual.isIsomorphicWith(expected)).isTrue();
        } finally {
            actual.close();
            expected.close();
        }
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
