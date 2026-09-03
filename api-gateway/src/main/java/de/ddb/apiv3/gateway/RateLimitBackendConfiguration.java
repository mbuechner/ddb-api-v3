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
package de.ddb.apiv3.gateway;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Selects only the state backend for Spring Cloud Gateway's shared Bucket4j limiter. */
@Configuration(proxyBeanMethods = false)
class RateLimitBackendConfiguration {

    private static final long MAX_LOCAL_BUCKETS = 10_000;
    private static final Duration KEEP_AFTER_REFILL = Duration.ofMinutes(1);

    /** Local state is bounded, process-local and discarded when the gateway stops. */
    @Bean
    @Profile("dev")
    AsyncProxyManager<String> localRateLimitBackend() {
        return Bucket4jCaffeine.<String>builderFor(
                        Caffeine.newBuilder().maximumSize(MAX_LOCAL_BUCKETS))
                .expirationAfterWrite(expirationStrategy())
                .build()
                .asAsync();
    }

    /**
     * Uses the standalone or Sentinel-aware Redis client configured and owned by Spring Boot, but
     * opens a dedicated connection with the key/value codecs required by Bucket4j. Lettuce resolves
     * the current primary through Sentinel and reconnects after a failover. The bean lifecycle
     * closes only this connection; Spring remains responsible for the underlying client.
     */
    @Bean(destroyMethod = "close")
    @Profile("prod")
    StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(
            LettuceConnectionFactory connectionFactory) {
        AbstractRedisClient nativeClient = connectionFactory.getRequiredNativeClient();
        if (!(nativeClient instanceof RedisClient redisClient)) {
            throw new IllegalStateException(
                    "Bucket4j requires a standalone or Sentinel Lettuce RedisClient");
        }
        return redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    /** Distributed state is shared by all gateway instances connected to the same Redis. */
    @Bean
    @Profile("prod")
    AsyncProxyManager<String> redisRateLimitBackend(
            StatefulRedisConnection<String, byte[]> bucket4jRedisConnection,
            @Value("${spring.data.redis.timeout:500ms}") Duration commandTimeout) {
        return Bucket4jLettuce.casBasedBuilder(bucket4jRedisConnection)
                .expirationAfterWrite(expirationStrategy())
                .requestTimeout(commandTimeout)
                .build()
                .asAsync();
    }

    private static ExpirationAfterWriteStrategy expirationStrategy() {
        return ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                KEEP_AFTER_REFILL);
    }
}
