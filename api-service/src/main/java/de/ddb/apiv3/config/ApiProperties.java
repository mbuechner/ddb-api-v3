package de.ddb.apiv3.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The public origin is configuration, so every endpoint can mint stable graph names. */
@ConfigurationProperties(prefix = "ddb.api")
public record ApiProperties(URI baseUrl) {

    public ApiProperties {
        if (baseUrl == null
                || !("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null
                || (baseUrl.getPort() != -1 && (baseUrl.getPort() < 1 || baseUrl.getPort() > 65535))
                || !("".equals(baseUrl.getRawPath()) || "/".equals(baseUrl.getRawPath()))) {
            throw new IllegalArgumentException(
                    "ddb.api.base-url must be an HTTP(S) origin without credentials, path, query or fragment");
        }
        String value = baseUrl.toString();
        if (value.endsWith("/")) {
            baseUrl = URI.create(value.substring(0, value.length() - 1));
        }
    }
}
