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

import java.util.List;
import java.util.Objects;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.config.HttpClientFactory;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType;
import org.springframework.cloud.gateway.config.HttpClientSslConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Keep Gateway's native client configuration, with a bounded fixed-pool waiting room. */
@Configuration(proxyBeanMethods = false)
final class GatewayHttpClientConfiguration {

    @Bean
    static HttpClientFactory gatewayHttpClientFactory(HttpClientProperties properties, ServerProperties serverProperties,
            HttpClientSslConfigurer sslConfigurer, List<HttpClientCustomizer> customizers) {
        return new HttpClientFactory(properties, serverProperties, sslConfigurer, customizers) {
            // Startup/shutdown ownership only; requests never change this resource reference.
            private volatile ConnectionProvider ownedPool;

            @Override
            protected ConnectionProvider buildConnectionProvider(HttpClientProperties configuration) {
                ConnectionProvider original = super.buildConnectionProvider(configuration);
                if (configuration.getPool().getType() != PoolType.FIXED) {
                    return original;
                }
                var builder = Objects.requireNonNull(original.mutate());
                original.dispose(); // This native FIXED provider is newly created and still unused.
                // Gateway otherwise overrides Reactor Netty's bounded default with -1 (unlimited).
                ownedPool = builder.pendingAcquireMaxCount(
                        Math.multiplyExact(configuration.getPool().getMaxConnections(), 2)).build();
                return ownedPool;
            }

            @Override
            protected void destroyInstance(HttpClient client) {
                final ConnectionProvider pool = ownedPool;
                if (pool != null) {
                    pool.dispose();
                }
            }
        };
    }
}
