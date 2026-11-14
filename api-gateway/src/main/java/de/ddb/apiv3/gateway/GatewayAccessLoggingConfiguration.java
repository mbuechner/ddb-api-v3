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

import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;

/** Native Netty completion callback: completed responses, except successful health probes. */
@Configuration(proxyBeanMethods = false)
final class GatewayAccessLoggingConfiguration {

    private static final Logger ACCESS = LoggerFactory.getLogger("de.ddb.apiv3.access");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    @Bean
    @ConditionalOnProperty(name = "ddb.logging.access.enabled", matchIfMissing = true)
    static NettyServerCustomizer gatewayAccessLogCustomizer(
            @Value("${ddb.gateway.client-id.header-name:X-Client-Id}") String clientIdHeader) {
        return server -> server.accessLog(true, args -> logCompletedRequest(args, clientIdHeader));
    }

    private static AccessLog logCompletedRequest(AccessLogArgProvider args, String clientIdHeader) {
        if (!ACCESS.isInfoEnabled()) {
            return null;
        }
        final var connection = args.connectionInformation();
        final String uri = args.uri() == null ? "" : args.uri().toString();
        final String path = connection instanceof HttpServerRequest request
                ? request.fullPath() : uri.split("\\?", 2)[0];
        final int status = Integer.parseInt(args.status().toString());
        if (status == 200 && ("/actuator/health/liveness".equals(path)
                || "/actuator/health/readiness".equals(path))) {
            return null;
        }
        final var event = ACCESS.atInfo()
                .addKeyValue("event.dataset", "http.access")
                .addKeyValue("event.kind", "event")
                .addKeyValue("event.duration", Math.max(0, args.duration()) * 1_000_000L)
                .addKeyValue("http.request.method", args.method())
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("network.protocol", "http");
        if (connection != null && connection.connectionRemoteAddress() instanceof InetSocketAddress peer
                && peer.getAddress() != null) {
            // Physical peer only; Forwarded headers do not establish a trusted identity.
            event.addKeyValue("source.ip", peer.getAddress().getHostAddress())
                    .addKeyValue("source.port", peer.getPort());
        }
        if (connection != null && connection.remoteAddress() instanceof InetSocketAddress client
                && client.getAddress() != null) {
            event.addKeyValue("client.ip", client.getAddress().getHostAddress())
                    .addKeyValue("client.port", client.getPort());
        }
        // HEAD/304 can advertise a representation's Content-Length while transferring no body.
        final long responseBytes = (args.method() != null && "HEAD".contentEquals(args.method()))
                || status < 200 || status == 204 || status == 304
                ? 0 : args.contentLength();
        if (responseBytes >= 0) {
            event.addKeyValue("http.response.body.bytes", responseBytes);
        }
        final String protocol = args.protocol();
        if (protocol != null) {
            event.addKeyValue("http.version", protocol.startsWith("HTTP/")
                    ? protocol.substring(5) : protocol);
        }
        event.addKeyValue("url.path", path);
        final String clientId = identifier(args.requestHeader(clientIdHeader));
        if (clientId != null) {
            event.addKeyValue("labels.client_id", clientId)
                    .addKeyValue("labels.client_id_source", "header");
        }
        final String responseId = identifier(args.responseHeader("X-Request-Id"));
        if (responseId != null) {
            event.addKeyValue("http.request.id", responseId)
                    .addKeyValue("labels.request_id_source", "response");
        } else if (connection instanceof HttpServerRequest request) {
            event.addKeyValue("http.request.id", request.requestId())
                    .addKeyValue("labels.request_id_source", "native");
        }
        addHeader(event, "user_agent.original", args.requestHeader("User-Agent"), 512);
        addHeader(event, "labels.request_content_type", args.requestHeader("Content-Type"), 256);
        addHeader(event, "labels.response_content_type", args.responseHeader("Content-Type"), 256);
        addHeader(event, "labels.request_content_encoding", args.requestHeader("Content-Encoding"), 128);
        addHeader(event, "labels.response_content_encoding", args.responseHeader("Content-Encoding"), 128);
        event.log("HTTP request completed");
        // The factory permits null. We already emitted the structured event, so skip NCSA text.
        return null;
    }

    private static String identifier(CharSequence value) {
        return value != null && value.length() <= 128 && IDENTIFIER.matcher(value).matches()
                ? value.toString() : null;
    }

    private static void addHeader(LoggingEventBuilder event, String key, CharSequence value, int maxLength) {
        if (value != null) {
            event.addKeyValue(key, value.subSequence(0, Math.min(value.length(), maxLength)).toString());
        }
    }
}
