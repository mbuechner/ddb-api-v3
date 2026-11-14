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
import static org.mockito.Mockito.mock;

import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WriteSecurityConfigurationTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withInitializer(application -> application.getBeanFactory()
                    .setConversionService(new ApplicationConversionService()))
            .withUserConfiguration(WriteSecurityTestApplication.class)
            .withBean(S3RdfDocumentStore.class, () -> mock(S3RdfDocumentStore.class))
            .withPropertyValues("ddb.api.base-url=https://api.example.test");

    @Test
    void absentIdentityProviderKeepsReadsAvailableAndWritesClosed() {
        context.run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(JwtDecoder.class);
            var mvc = MockMvcBuilders.webAppContextSetup(application.getSourceApplicationContext())
                    .addFilters(application.getBean(FilterChainProxy.class)).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/3/records/missing"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/3/records/missing"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/3/records/missing"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void incompleteTrustOrUnsafeDevTokenConfigurationPreventsStartup(String description, String[] properties) {
        context.withPropertyValues(properties).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    private static Stream<Arguments> invalidConfigurations() {
        String token = "ddb.security.dev-write-token=" + "A".repeat(43);
        return Stream.of(
                Arguments.of("issuer without audience", new String[] {
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.example.test"}),
                Arguments.of("audience without issuer", new String[] {
                        "spring.security.oauth2.resourceserver.jwt.audiences=ddb"}),
                Arguments.of("JWKS without issuer and audience", new String[] {
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://idp.example.test/jwks"}),
                Arguments.of("dev token in prod", new String[] {"spring.profiles.active=prod", token}),
                Arguments.of("dev plus prod profiles", new String[] {"spring.profiles.active=dev,prod", token}),
                Arguments.of("dev token without dev profile", new String[] {token}),
                Arguments.of("short dev token", new String[] {
                        "spring.profiles.active=dev", "ddb.security.dev-write-token=too-short"}),
                Arguments.of("padded dev token", new String[] {"spring.profiles.active=dev", token + "="}),
                Arguments.of("noncanonical dev token", new String[] {
                        "spring.profiles.active=dev", "ddb.security.dev-write-token=" + "A".repeat(42) + "B"}),
                Arguments.of("JWT and dev token together", new String[] {"spring.profiles.active=dev", token,
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.example.test",
                        "spring.security.oauth2.resourceserver.jwt.audiences=ddb"}));
    }
}
