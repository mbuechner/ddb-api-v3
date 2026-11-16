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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import jakarta.servlet.Filter;
import java.util.regex.Pattern;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Jetty logs once after the response, including time spent waiting for RDF capacity. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PerformanceLoggingProperties.class)
public final class HttpAccessLogConfiguration {

    private static final Logger ACCESS = LoggerFactory.getLogger("de.ddb.apiv3.access");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    @Bean
    @ConditionalOnProperty(prefix = "ddb.logging.access", name = "enabled", matchIfMissing = true)
    static WebServerFactoryCustomizer<JettyServletWebServerFactory> httpAccessLog(
            @Value("${ddb.logging.access.client-id-header-name:X-Client-Id}") String clientIdHeader,
            PerformanceLoggingProperties performance) {
        long slowThresholdNanos = performance.slowThreshold().toNanos();
        return factory -> factory.addServerCustomizers(server ->
                server.setRequestLog((request, response) -> log(request, response, clientIdHeader, slowThresholdNanos)));
    }

    @Bean
    static FilterRegistrationBean<Filter> requestTimingFilter(PerformanceLoggingProperties performance,
            @Value("${ddb.logging.access.enabled:true}") boolean accessEnabled) {
        Filter filter = (request, response, chain) -> {
            var timing = RequestTiming.get((jakarta.servlet.http.HttpServletRequest) request);
            timing.enable(performance.enabled() && accessEnabled && accessLogEnabled());
            ((jakarta.servlet.http.HttpServletResponse) response).setHeader(RequestTiming.HEADER, timing.requestIdValue());
            // ThreadLocal is only a bridge for synchronous MVC/service calls, scoped and cleared even on failure.
            try (var scope = timing.bind(); var stage = RequestTiming.start("application")) {
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    static boolean accessLogEnabled() {
        return ACCESS.isInfoEnabled();
    }

    private static void log(Request request, Response response, String clientIdHeader,
            long slowThresholdNanos) {
        if (!ACCESS.isInfoEnabled()) {
            return;
        }
        final String path = request.getHttpURI().getPath();
        if (response.getStatus() == 200
                && ("/actuator/health/liveness".equals(path) || "/actuator/health/readiness".equals(path))) {
            return;
        }
        var connection = request.getConnectionMetaData();
        long totalNanos = NanoTime.since(request.getBeginNanoTime());
        var event = ACCESS.atInfo()
                .addKeyValue("event.dataset", "http.access")
                .addKeyValue("event.kind", "event")
                .addKeyValue("event.duration", totalNanos)
                .addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", response.getStatus())
                .addKeyValue("http.request.body.bytes", Request.getContentBytesRead(request))
                .addKeyValue("http.response.body.bytes", Response.getContentBytesWritten(response))
                .addKeyValue("network.protocol", "http")
                .addKeyValue("http.version", connection.getHttpVersion().asString().substring(5));
        RequestTiming.get(request).addFields(event, totalNanos, slowThresholdNanos);

        // Read the transport, not an address claimed in Forwarded/X-Forwarded-For headers.
        var peer = connection.getConnection().getEndPoint().getRemoteSocketAddress();
        if (peer instanceof InetSocketAddress address && address.getAddress() != null) {
            event.addKeyValue("source.ip", address.getAddress().getHostAddress())
                    .addKeyValue("source.port", address.getPort());
        }
        if (connection.getRemoteSocketAddress() instanceof InetSocketAddress address) {
            try {
                // Jetty keeps forwarded addresses unresolved; parse literals without a DNS lookup.
                InetAddress client = address.getAddress() != null ? address.getAddress()
                        : InetAddress.ofLiteral(address.getHostString());
                event.addKeyValue("client.ip", client.getHostAddress())
                        .addKeyValue("client.port", address.getPort());
            } catch (IllegalArgumentException ignored) {
                // A forwarded hostname or "unknown" is not an ECS IP address.
            }
        }
        String clientId = request.getHeaders().get(clientIdHeader);
        if (clientId != null && IDENTIFIER.matcher(clientId).matches()) {
            event.addKeyValue("labels.client_id", clientId)
                    .addKeyValue("labels.client_id_source", "header");
        }
        String requestId = response.getHeaders().get("X-Request-Id");
        if (requestId != null && IDENTIFIER.matcher(requestId).matches()) {
            event.addKeyValue("http.request.id", requestId)
                    .addKeyValue("labels.request_id_source", "response");
        } else {
            // HTTP stream IDs are connection-local: include Jetty's connection ID too.
            event.addKeyValue("http.request.id", connection.getId() + ":" + request.getId())
                    .addKeyValue("labels.request_id_source", "native");
        }
        addHeader(event, "user_agent.original", request.getHeaders().get("User-Agent"), 512);
        addHeader(event, "labels.request_content_type", request.getHeaders().get("Content-Type"), 256);
        addHeader(event, "labels.response_content_type", response.getHeaders().get("Content-Type"), 256);
        addHeader(event, "labels.request_content_encoding", request.getHeaders().get("Content-Encoding"), 128);
        addHeader(event, "labels.response_content_encoding", response.getHeaders().get("Content-Encoding"), 128);
        event.log("HTTP request completed");
    }

    private static void addHeader(LoggingEventBuilder event, String key, String value, int maxLength) {
        if (value != null) {
            event.addKeyValue(key, value.substring(0, Math.min(value.length(), maxLength)));
        }
    }
}
