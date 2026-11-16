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

package de.ddb.apiv3.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiVersionRedirectControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiVersionRedirectController()).build();

    @ParameterizedTest
    @ValueSource(strings = {"records", "items", "organizations", "datasets", "profiles", "source", "future-resource"})
    void redirectsResourceNamespacesWithoutCheckingWhetherTheTargetExists(String resource) throws Exception {
        mvc.perform(get("/" + resource + "/not-an-id/subresource"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "/3/" + resource + "/not-an-id/subresource"))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"})
    void usesAMethodPreservingRedirect(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/records/" + "A".repeat(32)).content("body"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "/3/records/" + "A".repeat(32)));
    }

    @Test
    void preservesRawEncodingQueryAndTheServletContextWithoutAcceptingAnotherOrigin() throws Exception {
        mvc.perform(get(URI.create("/app/records/a%2Fb?q=a%2Fb&next=//other.example/path"))
                        .contextPath("/app").header("Host", "untrusted.example"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "/app/3/records/a%2Fb?q=a%2Fb&next=//other.example/path"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/records", "/records/"})
    void alsoRedirectsCollectionUrls(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "/3" + path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/3/records/anything", "/4/items/anything", "/actuator/health", "/openapi/openapi.yaml",
            "/webjars/swagger-ui/index.html", "/swagger-ui/index.html", "/swagger-ui.html", "/error",
            "/contexts/record-metadata-v1.jsonld", "/favicon.ico"})
    void doesNotRedirectVersionedOrInfrastructurePaths(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"));
    }
}
