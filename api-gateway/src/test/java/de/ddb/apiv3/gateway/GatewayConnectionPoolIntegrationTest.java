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

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.config.HttpClientFactory;
import org.springframework.context.annotation.Bean;
import reactor.netty.http.client.HttpClient;

/** Real concurrent HTTP: reject excess waiting requests instead of retaining an unbounded queue. */
class GatewayConnectionPoolIntegrationTest {

    @Test
    void boundsTheNativePoolAndPreservesClientCustomizationAndLifecycle() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var backendCalls = new AtomicInteger();
        final var customizedCalls = new AtomicInteger();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var backendExecutor = Executors.newVirtualThreadPerTaskExecutor();
                var client = java.net.http.HttpClient.newHttpClient()) {
            backend.setExecutor(backendExecutor);
            backend.createContext("/", exchange -> {
                try (exchange) {
                    if ("active".equals(exchange.getRequestHeaders().getFirst("X-Factory-Customizer"))) {
                        customizedCalls.incrementAndGet();
                    }
                    if (backendCalls.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            backend.start();
            try (var gateway = new SpringApplicationBuilder(DdbApiGatewayApplication.class, Customization.class)
                    .web(WebApplicationType.REACTIVE)
                    .run("--APP_PROFILE=dev", "--GATEWAY_PORT=0",
                            "--GATEWAY_API_SERVICE_URI=http://127.0.0.1:" + backend.getAddress().getPort(),
                            "--GATEWAY_HTTP_MAX_CONNECTIONS=1",
                            "--spring.cloud.gateway.server.webflux.httpclient.pool.name=bounded-pool-test",
                            "--spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout=10000",
                            "--spring.config.import=optional:file:./missing-pool-test.env[.properties]",
                            "--logging.level.root=OFF", "--spring.main.banner-mode=off")) {
                assertThat(gateway.getBeansOfType(HttpClient.class)).hasSize(1);
                assertThat(gateway.getBean("&gatewayHttpClientFactory", HttpClientFactory.class).getClass()
                        .getEnclosingClass()).isEqualTo(GatewayHttpClientConfiguration.class);
                final var pool = gateway.getBean(HttpClient.class).configuration().connectionProvider();
                assertThat(pool.maxConnections()).isEqualTo(1);
                assertThat(pool.name()).isEqualTo("bounded-pool-test");
                final int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
                CompletableFuture<HttpResponse<Void>> first = get(client, port, 0);
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    List<CompletableFuture<HttpResponse<Void>>> queued = IntStream.range(1, 4)
                            .mapToObj(index -> get(client, port, index)).toList();
                    final var rejected = (HttpResponse<?>) CompletableFuture.anyOf(
                            queued.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
                    assertThat(rejected.statusCode()).isEqualTo(500);
                    assertThat(backendCalls).hasValue(1);
                    release.countDown();
                    assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                    assertThat(queued.stream().map(future -> future.join().statusCode()).toList())
                            .containsExactlyInAnyOrder(200, 200, 500);
                    assertThat(backendCalls).hasValue(3);
                    assertThat(customizedCalls).hasValue(3);
                } finally {
                    release.countDown();
                }
                // Closing a context must release its own pool, not Reactor Netty's global resources.
                gateway.close();
                assertThat(pool.isDisposed()).isTrue();
            } finally {
                release.countDown();
                backend.stop(0);
            }
        }
    }

    private static CompletableFuture<HttpResponse<Void>> get(java.net.http.HttpClient client, int port, int index) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/3/probe"))
                .header("X-Client-Id", "pool-client-" + index).GET().build(), HttpResponse.BodyHandlers.discarding());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static final class Customization {
        @Bean
        static HttpClientCustomizer markerHeader() {
            return client -> client.headers(headers -> headers.set("X-Factory-Customizer", "active"));
        }
    }
}
