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
package de.ddb.apiv3.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.ddb.apiv3.storage.StorageException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3StorageConfigurationTest {

    private final S3Client client = mock(S3Client.class);
    private final S3StorageConfiguration configuration = new S3StorageConfiguration();

    @Test
    void leavesBucketProvisioningToTheOperatorByDefault() throws Exception {
        configuration.s3BucketInitializer(client, properties(false)).afterPropertiesSet();

        verifyNoInteractions(client);
    }

    @Test
    void createsOnlyAMissingBucketWhenExplicitlyEnabled() throws Exception {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        configuration.s3BucketInitializer(client, properties(true)).afterPropertiesSet();

        var request = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(client).createBucket(request.capture());
        assertThat(request.getValue().createBucketConfiguration().locationConstraintAsString())
                .isEqualTo("eu-central-1");
    }

    @Test
    void neverCreatesABucketAfterAnAuthorizationFailure() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> configuration.s3BucketInitializer(client, properties(true)).afterPropertiesSet())
                .isInstanceOf(StorageException.class);
        verify(client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void marksStorageUnreadyWithoutExposingClientExceptionDetails() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("signed request with credential details"));

        var health = configuration.s3StorageHealthIndicator(client, properties(false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
        var request = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(client).headBucket(request.capture());
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(2));
    }

    @Test
    void neverRetriesAnUncertainConditionalWriteAsAFalseConflict() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                // Pretend the first write committed but its success reply became a proxy error.
                int status = attempts.incrementAndGet() == 1 ? 503 : 412;
                String code = status == 503 ? "ServiceUnavailable" : "PreconditionFailed";
                byte[] error = ("<Error><Code>" + code + "</Code></Error>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(status, error.length);
                exchange.getResponseBody().write(error);
            }
        });
        server.start();
        var properties = new S3StorageProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "eu-central-1", "records", "test-access", "test-secret", true,
                Duration.ofSeconds(1), Duration.ofSeconds(2), false);
        try (S3Client actualClient = configuration.s3Client(properties,
                configuration.s3CredentialsProvider(properties))) {
            var repository = new S3RdfDocumentStore(actualClient, properties);

            assertThatThrownBy(() -> repository.create("J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3", "record",
                    "RDF bytes".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(StorageException.class);
            assertThat(attempts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotPrintSecretsEvenWhenTheEndpointIsInvalid() {
        var properties = new S3StorageProperties(
                URI.create("http://user:uri-secret@localhost/?token=query-secret#fragment-secret"),
                "eu-central-1", "records", "access-secret", "secret-secret", true,
                Duration.ofSeconds(1), Duration.ofSeconds(2), false);

        assertThat(properties.toString()).doesNotContain(
                "uri-secret", "query-secret", "fragment-secret", "access-secret", "secret-secret");
        assertThat(properties.isEndpointValid()).isFalse();
    }

    private static S3StorageProperties properties(boolean createBucket) {
        return new S3StorageProperties(URI.create("http://127.0.0.1:8333"), "eu-central-1", "records",
                "test-access", "test-secret", true, Duration.ofSeconds(1), Duration.ofSeconds(2), createBucket);
    }
}
