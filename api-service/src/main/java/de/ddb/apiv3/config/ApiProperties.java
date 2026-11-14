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

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriComponentsBuilder;

/** An explicit origin stays canonical; auto follows the current HTTP request. */
@ConfigurationProperties(prefix = "ddb.api")
public record ApiProperties(URI baseUrl) {

    private static final URI AUTO = URI.create("auto");

    public ApiProperties {
        if (!AUTO.equals(baseUrl)) {
            if (baseUrl == null
                    || !("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                    || baseUrl.getHost() == null
                    || baseUrl.getRawUserInfo() != null
                    || baseUrl.getRawQuery() != null
                    || baseUrl.getRawFragment() != null
                    || (baseUrl.getPort() != -1 && (baseUrl.getPort() < 1 || baseUrl.getPort() > 65535))
                    || !("".equals(baseUrl.getRawPath()) || "/".equals(baseUrl.getRawPath()))) {
                throw new IllegalArgumentException(
                        "ddb.api.base-url must be auto or an HTTP(S) origin without credentials, path, query or fragment");
            }
            String value = baseUrl.toString();
            if (value.endsWith("/")) {
                baseUrl = URI.create(value.substring(0, value.length() - 1));
            }
        }
    }

    /** Strip transport paths such as /3/records/ID before Jena resolves resource IRIs. */
    public URI resolveOrigin(URI requestUri) {
        if (!AUTO.equals(baseUrl)) {
            return baseUrl;
        }
        URI origin = UriComponentsBuilder.fromUri(requestUri)
                .replacePath(null).replaceQuery(null).fragment(null).build(true).toUri();
        return new ApiProperties(origin).baseUrl();
    }
}
