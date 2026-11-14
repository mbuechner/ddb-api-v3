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

package de.ddb.apiv3.storage.s3;

import static org.springframework.util.StringUtils.hasText;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Connection settings shared by the S3 client, document store and readiness check. */
@Validated
@ConfigurationProperties(prefix = "ddb.storage.s3")
public record S3StorageProperties(
        @NotNull URI endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        @DefaultValue("50") @Min(1) int maxConnections,
        @NotNull @DurationMin(millis = 1) Duration connectionTimeout,
        @NotNull @DurationMin(millis = 1) Duration readTimeout,
        boolean createBucket) {

    @AssertTrue(message = "S3 endpoint must be an HTTP(S) origin without credentials, query or fragment")
    public boolean isEndpointValid() {
        return endpoint != null
                && ("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                && endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null
                && endpoint.getFragment() == null
                && (endpoint.getPort() == -1 || (endpoint.getPort() > 0 && endpoint.getPort() <= 65535))
                && (endpoint.getPath() == null || endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()));
    }

    @AssertTrue(message = "S3 access-key and secret-key must either both be set or both be absent")
    public boolean isCredentialPairValid() {
        return hasText(accessKey) == hasText(secretKey);
    }

    /** Never include credential values in configuration diagnostics. */
    @Override
    public String toString() {
        return "S3StorageProperties[region=" + region + ", bucket=" + bucket + "]";
    }
}
