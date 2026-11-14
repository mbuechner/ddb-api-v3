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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiPropertiesTest {

    @ParameterizedTest
    @CsvSource({
        "https://rdf.example.org/, https://rdf.example.org",
        "http://localhost:8080, http://localhost:8080",
        "https://[::1]:8443/, https://[::1]:8443"
    })
    void acceptsHttpOriginsAndRemovesTheOptionalTrailingSlash(String configured, String expected) {
        assertThat(new ApiProperties(URI.create(configured)).baseUrl()).isEqualTo(URI.create(expected));
    }

    @ParameterizedTest
    @CsvSource({
        "http://localhost:8080/3/records/ID?format=xml#part, http://localhost:8080",
        "https://api.example.org/3/records/ID, https://api.example.org",
        "https://api.example.org:443/3/records/ID, https://api.example.org:443",
        "https://api.example.org:8443/app/3/records/ID?q=value, https://api.example.org:8443",
        "https://[::1]:8443/3/records/ID, https://[::1]:8443"
    })
    void autoUsesOnlyTheRequestOriginWithoutApiVersionPathQueryOrFragment(String request, String expected) {
        assertThat(new ApiProperties(URI.create("auto")).resolveOrigin(URI.create(request)))
                .isEqualTo(URI.create(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/relative", "ftp://example.org/3/records/ID", "https://user:password@example.org/path"})
    void autoStillRequiresAnAbsoluteHttpOrigin(String request) {
        URI requestUri = request == null ? null : URI.create(request);
        assertThatThrownBy(() -> new ApiProperties(URI.create("auto")).resolveOrigin(requestUri))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"http://localhost:8080/3/records/ID", "https://other.example.org:8443/path?query=value"})
    void anExplicitOriginOverridesTheRequest(String request) {
        URI requestUri = request == null ? null : URI.create(request);
        assertThat(new ApiProperties(URI.create("https://canonical.example.org/")).resolveOrigin(requestUri))
                .isEqualTo(URI.create("https://canonical.example.org"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "/", "/relative", "rdf.example.org", "AUTO", "auto/", "ftp://rdf.example.org", "https:///missing-host",
        "https://user:password@rdf.example.org", "https://rdf.example.org/path",
        "https://rdf.example.org?query=value", "https://rdf.example.org#fragment",
        "https://rdf.example.org:0", "https://rdf.example.org:65536"
    })
    void rejectsValuesThatAreNotAnHttpOrigin(String configured) {
        URI baseUrl = configured == null ? null : URI.create(configured);

        assertThatThrownBy(() -> new ApiProperties(baseUrl)).isInstanceOf(IllegalArgumentException.class);
    }
}
