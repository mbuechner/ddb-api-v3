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

import de.ddb.apiv3.storage.StorageException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** One S3 client for all profiles. Spring owns its sockets and credential-provider lifecycle. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
public final class S3StorageConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(S3StorageConfiguration.class);
    private static final String HEAD_BUCKET = "HeadBucket";

    @Bean
    static AwsCredentialsProvider s3CredentialsProvider(S3StorageProperties properties) {
        return properties.accessKey() == null || properties.accessKey().isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKey(), properties.secretKey()));
    }

    @Bean(destroyMethod = "close")
    static S3Client s3Client(S3StorageProperties properties, AwsCredentialsProvider credentialsProvider) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(properties.pathStyleAccess())
                // Avoid optional AWS checksum trailers on this S3-compatible endpoint.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(properties.maxConnections())
                        // Bound pool contention too; virtual threads must not wait indefinitely.
                        .connectionAcquisitionTimeout(properties.connectionTimeout())
                        .connectionTimeout(properties.connectionTimeout())
                        .socketTimeout(properties.readTimeout()))
                .overrideConfiguration(config -> config
                        // A lost success reply must stay an unknown result, not become a retry's 412.
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(properties.connectionTimeout().plus(properties.readTimeout())))
                .build();
    }

    /** Only explicitly enabled development setups provision the bucket automatically. */
    @Bean
    static InitializingBean s3BucketInitializer(S3Client client, S3StorageProperties properties) {
        return () -> {
            if (!properties.createBucket()) {
                return;
            }
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            } catch (S3Exception exception) {
                if (exception.statusCode() != 404) {
                    throw initializationFailed(properties, HEAD_BUCKET, exception);
                }
                // HEAD has no XML error body. In this bucket-only operation 404 means
                // NoSuchBucket; authorization failures (403) must never trigger creation.
                try {
                    var request = CreateBucketRequest.builder().bucket(properties.bucket());
                    if (!Region.US_EAST_1.id().equals(properties.region())) {
                        request.createBucketConfiguration(config -> config.locationConstraint(properties.region()));
                    }
                    client.createBucket(request.build());
                } catch (BucketAlreadyOwnedByYouException alreadyCreated) {
                    // A second service instance may have provisioned this bucket concurrently.
                } catch (SdkException creationFailure) {
                    throw initializationFailed(properties, "CreateBucket", creationFailure);
                }
            } catch (SdkException exception) {
                throw initializationFailed(properties, HEAD_BUCKET, exception);
            }
        };
    }

    @Bean("s3Storage")
    static HealthIndicator s3StorageHealthIndicator(S3Client client, S3StorageProperties properties) {
        final var lastFailure = new AtomicReference<Health>();
        return () -> {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket())
                        // Keep a sleepy store inside the Kubernetes readiness probe's budget.
                        .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(2)))
                        .build());
                if (lastFailure.getAndSet(null) != null) {
                    bucketEvent(LOG.atInfo(), properties, HEAD_BUCKET)
                            .addKeyValue("event.action", "s3_readiness_recovered")
                            .log("S3 bucket readiness check recovered");
                }
                return Health.up().build();
            } catch (SdkException exception) {
                final var failure = bucketDown(exception);
                if (!failure.equals(lastFailure.getAndSet(failure))) {
                    final var event = bucketEvent(LOG.atWarn(), properties, HEAD_BUCKET)
                            .addKeyValue("event.action", "s3_readiness_failed");
                    withFailureDetails(event, failure)
                            .log("S3 bucket readiness check failed");
                }
                return failure;
            }
        };
    }

    private static Health bucketDown(SdkException exception) {
        final var health = Health.down().withDetail("error",
                NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
        if (exception instanceof S3Exception s3Exception) {
            health.withDetail("httpStatus", s3Exception.statusCode());
        }
        return health.build();
    }

    private static LoggingEventBuilder bucketEvent(
            LoggingEventBuilder event, S3StorageProperties properties, String operation) {
        // Configuration binding validates that this URI is a plain HTTP(S) origin.
        return event.addKeyValue("s3.endpoint", properties.endpoint().toASCIIString())
                .addKeyValue("s3.bucket", properties.bucket())
                .addKeyValue("s3.region", properties.region())
                .addKeyValue("s3.path_style_access", properties.pathStyleAccess())
                .addKeyValue("s3.operation", operation);
    }

    private static LoggingEventBuilder withFailureDetails(LoggingEventBuilder event, Health failure) {
        final var details = failure.getDetails();
        event.addKeyValue("s3.error", details.get("error"));
        if (details.get("httpStatus") instanceof Integer status && status > 0) {
            event.addKeyValue("s3.http_status", status);
        }
        return event;
    }

    private static StorageException initializationFailed(
            S3StorageProperties properties, String operation, SdkException exception) {
        final var event = bucketEvent(LOG.atError(), properties, operation)
                .addKeyValue("event.action", "s3_bucket_initialization_failed");
        withFailureDetails(event, bucketDown(exception)).log("S3 bucket initialization failed");
        // Spring logs the startup exception. Report only the structured diagnostics above.
        return new StorageException("The S3 record bucket is unavailable");
    }
}
