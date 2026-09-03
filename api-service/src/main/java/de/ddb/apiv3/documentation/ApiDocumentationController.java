/*
 * Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This code was generated with assistance from OpenAI ChatGPT.
 */
package de.ddb.apiv3.documentation;

import de.ddb.apiv3.record.metadata.RecordMetadataContextDocument;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the build's authoritative OpenAPI contract with its correct media type.
 *
 * <p>Swagger UI consumes this resource directly, so runtime documentation cannot diverge from
 * the file used for source generation.</p>
 */
@RestController
public class ApiDocumentationController {

    private final RecordMetadataContextDocument metadataContext;

    public ApiDocumentationController(RecordMetadataContextDocument metadataContext) {
        this.metadataContext = metadataContext;
    }

    /**
     * Sends browser clients from the service root to its interactive API documentation.
     *
     * <p>The relative target deliberately preserves the current host, port, and reverse-proxy
     * origin. The redirect therefore works for a direct developer start and through the gateway
     * without environment-specific URL construction.</p>
     *
     * @return an empty HTTP 302 response pointing to Swagger UI
     */
    @GetMapping("/")
    public ResponseEntity<Void> redirectToSwaggerUi() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/swagger-ui.html"))
                .build();
    }

    /**
     * Returns the contract copied to the application classpath by Maven.
     *
     * @return the packaged OpenAPI YAML resource
     */
    @GetMapping(value = "/openapi/openapi.yaml", produces = "application/yaml")
    public Resource openApiContract() {
        return new ClassPathResource("static/openapi/openapi.yaml");
    }

    /**
     * Serves the versioned JSON-LD context generated once during application startup.
     *
     * <p>The versioned path makes a one-year immutable cache safe. A semantic context change must
     * therefore be published under a new path instead of changing this document in place.</p>
     */
    @GetMapping(value = RecordMetadataContextDocument.PATH, produces = "application/ld+json")
    public ResponseEntity<Resource> recordMetadataContext() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(metadataContext.resource());
    }

}
