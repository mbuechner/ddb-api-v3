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

import de.ddb.apiv3.error.ApiExceptionHandler;
import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.records.RecordUploadService;
import de.ddb.apiv3.records.RecordsController;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The actual record controller and RDF validator; only the S3 repository is mocked. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(ApiProperties.class)
@Import({WriteSecurityConfiguration.class, RecordsController.class, RecordUploadService.class,
        RdfCodec.class, ApiExceptionHandler.class, ApiVersionRedirectController.class})
class WriteSecurityTestApplication {
}
