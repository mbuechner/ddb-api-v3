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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RequestConcurrencyConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void bindsBoundedDefaultsAndAllowsFailFastWithoutAQueue() {
        context.run(application -> {
            var limits = application.getBean(RequestConcurrencyConfiguration.Limits.class);
            assertThat(limits.maxRequests()).isEqualTo(32);
            assertThat(limits.maxQueuedRequests()).isEqualTo(64);
            assertThat(limits.queueTimeout()).isEqualTo(Duration.ofSeconds(1));
        });
        context.withPropertyValues("ddb.api.concurrency.max-queued-requests=0").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(RequestConcurrencyConfiguration.Limits.class).maxQueuedRequests()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-requests=0", "max-requests=-1", "max-queued-requests=-1",
            "queue-timeout=0ms", "queue-timeout=-1ms", "queue-timeout=1ns"})
    void rejectsUnboundedOrInvalidLimits(String setting) {
        context.withPropertyValues("ddb.api.concurrency." + setting)
                .run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RequestConcurrencyConfiguration.Limits.class)
    static class PropertiesOnly {
    }
}
