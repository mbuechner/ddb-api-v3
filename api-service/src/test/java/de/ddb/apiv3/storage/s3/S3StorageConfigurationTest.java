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
import jakarta.validation.Validation;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3StorageConfigurationTest {

    private final S3Client client = mock(S3Client.class);

    @Test
    void leavesBucketProvisioningToTheOperatorByDefault() throws Exception {
        S3StorageConfiguration.s3BucketInitializer(client, properties(false)).afterPropertiesSet();

        verifyNoInteractions(client);
    }

    @Test
    void createsOnlyAMissingBucketWhenExplicitlyEnabled() throws Exception {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        S3StorageConfiguration.s3BucketInitializer(client, properties(true)).afterPropertiesSet();

        var request = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(client).createBucket(request.capture());
        assertThat(request.getValue().createBucketConfiguration().locationConstraintAsString())
                .isEqualTo("eu-central-1");
    }

    @Test
    void neverCreatesABucketAfterAnAuthorizationFailure() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> S3StorageConfiguration.s3BucketInitializer(client, properties(true)).afterPropertiesSet())
                .isInstanceOf(StorageException.class);
        verify(client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void reportsOnlySafeClientFailureMetadata() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.builder().message("signed request with credential details")
                        .cause(new UnknownHostException("private.storage.host")).build());

        var health = S3StorageConfiguration.s3StorageHealthIndicator(client, properties(false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error").containsEntry("error", "UnknownHostException");
        assertThat(health.toString()).doesNotContain("credential details", "signed request", "private.storage.host");
        var request = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(client).headBucket(request.capture());
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(2));
    }

    @Test
    void reportsS3HttpStatusWithoutExposingExceptionMessages() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("private credential details").build());

        final var health = S3StorageConfiguration.s3StorageHealthIndicator(client, properties(false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("error", "httpStatus")
                .containsEntry("error", "S3Exception").containsEntry("httpStatus", 403);
        assertThat(health.toString()).doesNotContain("private credential details");
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
                "eu-central-1", "records", "test-access", "test-secret", true, 50,
                Duration.ofSeconds(1), Duration.ofSeconds(2), false);
        try (S3Client actualClient = S3StorageConfiguration.s3Client(properties,
                S3StorageConfiguration.s3CredentialsProvider(properties))) {
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
    void boundsWaitingForASaturatedConnectionPool() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (requests.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                }
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        var properties = new S3StorageProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "eu-central-1", "records", "test-access", "test-secret", true, 1,
                Duration.ofMillis(250), Duration.ofSeconds(5), false);
        try (var actualClient = S3StorageConfiguration.s3Client(properties,
                S3StorageConfiguration.s3CredentialsProvider(properties));
                var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = calls.submit(() -> actualClient.headBucket(request -> request.bucket("records")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                assertThatThrownBy(() -> actualClient.headBucket(request -> request.bucket("records")))
                        .isInstanceOf(SdkClientException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(requests).hasValue(1);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            server.stop(0);
            serverExecutor.close();
        }
    }

    @Test
    void doesNotPrintSecretsEvenWhenTheEndpointIsInvalid() {
        var properties = new S3StorageProperties(
                URI.create("http://user:uri-secret@localhost/?token=query-secret#fragment-secret"),
                "eu-central-1", "records", "access-secret", "secret-secret", true, 50,
                Duration.ofSeconds(1), Duration.ofSeconds(2), false);

        assertThat(properties.toString()).doesNotContain(
                "uri-secret", "query-secret", "fragment-secret", "access-secret", "secret-secret");
        assertThat(properties.isEndpointValid()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "-PT0.000000001S", "PT0.000000001S", "PT0.000999999S"})
    void rejectsTimeoutsThatWouldDisableMillisecondBasedTransportLimits(String configured) {
        Duration timeout = configured == null ? null : Duration.parse(configured);
        var properties = new S3StorageProperties(URI.create("http://localhost:8333"), "eu-central-1", "records",
                null, null, true, 50, timeout, timeout, false);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("connectionTimeout", "readTimeout");
        }
    }

    @Test
    void acceptsTheSmallestSupportedTransportTimeout() {
        var properties = new S3StorageProperties(URI.create("http://localhost:8333"), "eu-central-1", "records",
                null, null, true, 50, Duration.ofMillis(1), Duration.ofMillis(1), false);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveConnectionPoolSizes(int maxConnections) {
        var properties = new S3StorageProperties(URI.create("http://localhost:8333"), "eu-central-1", "records",
                null, null, true, maxConnections, Duration.ofSeconds(1), Duration.ofSeconds(2), false);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("maxConnections");
        }
    }

    private static S3StorageProperties properties(boolean createBucket) {
        return new S3StorageProperties(URI.create("http://127.0.0.1:8333"), "eu-central-1", "records",
                "test-access", "test-secret", true, 50, Duration.ofSeconds(1), Duration.ofSeconds(2), createBucket);
    }
}
