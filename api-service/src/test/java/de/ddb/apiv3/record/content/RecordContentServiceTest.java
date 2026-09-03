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
package de.ddb.apiv3.record.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.ddb.apiv3.record.StoredRecordContent;
import de.ddb.apiv3.record.StoredRecordMetadata;
import de.ddb.apiv3.record.persistence.RecordRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers failures caused by corrupt persisted content rather than invalid client input.
 *
 * <p>Mocks are intentional: these states cannot be created through the validated upload API. A
 * missing digest or unparsable stored RDF therefore remains a permanent server-side failure and
 * must not be reported as a negotiable or client-correctable response.</p>
 */
class RecordContentServiceTest {

    private static final String RECORD_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @Test
    void reportsMissingStoredDigestAsPermanentInternalFailure() {
        StoredRecordMetadata metadata = mock(StoredRecordMetadata.class);
        when(metadata.isTombstone()).thenReturn(false);
        when(metadata.sha256()).thenReturn(null);
        RecordContentService service = serviceWith(metadata, new byte[0]);

        assertThatThrownBy(() -> service.getRepresentation(
                        RECORD_ID, List.of(MediaType.parseMediaType("application/rdf+xml"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void reportsInvalidStoredRdfAsPermanentInternalFailure() {
        StoredRecordMetadata metadata = mock(StoredRecordMetadata.class);
        when(metadata.isTombstone()).thenReturn(false);
        when(metadata.sha256()).thenReturn("0".repeat(64));
        RecordContentService service = serviceWith(
                metadata, "not RDF/XML".getBytes(StandardCharsets.UTF_8));

        var representation = service.getRepresentation(
                RECORD_ID, List.of(MediaType.parseMediaType("application/ld+json")));

        assertThatThrownBy(representation::body)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private static RecordContentService serviceWith(StoredRecordMetadata metadata, byte[] rdfXml) {
        RecordRepository repository = mock(RecordRepository.class);
        when(repository.findContentById(RECORD_ID))
                .thenReturn(Optional.of(new StoredRecordContent(metadata, rdfXml)));
        return new RecordContentService(repository, "https://api.example.org");
    }
}
