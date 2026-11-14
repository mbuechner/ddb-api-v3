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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.util.Callback;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectWriter;

/** Virtual threads are cheap; RDF graphs are not. Jetty bounds work before reading bodies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestConcurrencyConfiguration.Limits.class)
public final class RequestConcurrencyConfiguration {

    @Bean
    static WebServerFactoryCustomizer<JettyServletWebServerFactory> requestConcurrency(Limits limits) {
        return factory -> factory.addServerCustomizers(server -> {
            var qos = new ProblemQoSHandler();
            qos.includePath("/3/*");
            qos.setMaxRequestCount(limits.maxRequests());
            qos.setMaxSuspendedRequestCount(limits.maxQueuedRequests());
            qos.setMaxSuspend(limits.queueTimeout());
            server.insertHandler(qos);
        });
    }

    @Validated
    @ConfigurationProperties("ddb.api.concurrency")
    public record Limits(
            @DefaultValue("32") @Min(1) int maxRequests,
            @DefaultValue("64") @Min(0) int maxQueuedRequests,
            @DefaultValue("1s") @NotNull @DurationMin(millis = 1) Duration queueTimeout) {
    }

    /** Keep Spring's ProblemDetail representation even when Jetty rejects before MVC. */
    private static final class ProblemQoSHandler extends QoSHandler {

        private static final ObjectWriter PROBLEMS = new JacksonJsonHttpMessageConverter()
                .getMapper().writerFor(ProblemDetail.class);

        @Override
        protected void reject(Request request, Response response, Callback callback, int status) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),
                    "This API instance is at capacity; retry later");
            String requestId = UUID.randomUUID().toString();
            problem.setProperty("requestId", requestId);
            try {
                byte[] body = PROBLEMS.writeValueAsBytes(problem);
                response.setStatus(status);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                response.getHeaders().put("X-Request-Id", requestId);
                response.write(true, ByteBuffer.wrap(body), callback);
            } catch (RuntimeException exception) {
                callback.failed(exception);
            }
        }

        @Override
        protected void failSuspended(Request request, Response response, Callback callback,
                int status, Throwable failure) {
            reject(request, response, callback, status);
        }
    }
}
