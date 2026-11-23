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
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

class GatewayAccessLoggingConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GatewayAccessLoggingConfiguration.class);

    @Test
    void enablesNativeAccessLoggingByDefault() {
        context.run(application -> assertThat(application).hasSingleBean(NettyServerCustomizer.class));
    }

    @Test
    void canDisableAccessLogging() {
        context.withPropertyValues("ddb.logging.access.enabled=false")
                .run(application -> assertThat(application).doesNotHaveBean(NettyServerCustomizer.class));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "rfc-7239|Forwarded|for=203.0.113.25;proto=https;host=public.example",
            "x-forwarded|X-Forwarded-For|203.0.113.25"
    }, delimiter = '|')
    void distinguishesThePhysicalPeerFromAnExplicitlyTrustedForwardedClient(
            String naming, String header, String headerValue) {
        try (final var gateway = new SpringApplicationBuilder(DdbApiGatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run("--APP_PROFILE=dev", "--GATEWAY_PORT=0", "--GATEWAY_FORWARD_HEADERS_STRATEGY=native",
                        "--GATEWAY_FORWARD_HEADERS_NAMING=" + naming,
                        "--management.endpoints.web.exposure.include=health", "--spring.main.banner-mode=off")) {
            final Logger logger = (Logger) LoggerFactory.getLogger("de.ddb.apiv3.access");
            final var appender = new ListAppender<ILoggingEvent>();
            appender.list = new CopyOnWriteArrayList<>();
            appender.start();
            logger.addAppender(appender);
            try {
                final int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
                WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
                        .get().uri("/contexts/access-native-forwarded")
                        .header(header, headerValue)
                        .exchange().expectStatus().isNotFound().expectBody().returnResult();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(appender.list).hasSize(1));
                final var fields = appender.list.getFirst().getKeyValuePairs().stream()
                        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
                assertThat(fields).containsEntry("source.ip", "127.0.0.1")
                        .containsEntry("client.ip", "203.0.113.25");
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }
}
