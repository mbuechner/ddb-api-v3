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
package de.ddb.apiv3.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.ddb.apiv3.storage.StorageException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Verifies exact binary objects, S3 headers and conditional writes without a storage emulator. */
class S3RdfDocumentStoreTest {

    private static final String ID = "J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3";
    private static final byte[] JELLY = {0, 127, (byte) 128, (byte) 255};
    private final S3Client client = mock(S3Client.class);
    private final S3RdfDocumentStore repository = new S3RdfDocumentStore(client, properties());

    @Test
    void createsTheExactJellyObjectInOneConditionalWrite() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"created\"").build());

        assertThat(repository.create(ID, "record", JELLY)).contains("\"created\"");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("records");
        assertThat(request.getValue().key()).isEqualTo(ID + "/record");
        assertThat(request.getValue().contentType()).isEqualTo("application/x-jelly-rdf");
        assertThat(request.getValue().contentEncoding()).isNull();
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().ifMatch()).isNull();
        assertThat(bytes(body.getValue())).containsExactly(JELLY);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"previous\"", "*"})
    void replacesOnlyTheNamedObjectWithoutAnAdditionalLookup(String ifMatch) throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"updated\"").build());

        assertThat(repository.replace(ID, "record", JELLY, ifMatch)).contains("\"updated\"");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().key()).isEqualTo(ID + "/record");
        assertThat(request.getValue().contentType()).isEqualTo("application/x-jelly-rdf");
        assertThat(request.getValue().contentEncoding()).isNull();
        assertThat(request.getValue().ifMatch()).isEqualTo(ifMatch);
        assertThat(request.getValue().ifNoneMatch()).isNull();
        assertThat(bytes(body.getValue())).containsExactly(JELLY);
        verifyNoMoreInteractions(client);
    }

    @Test
    void supportsSeveralMethodSelectedNamesUnderTheSameDdbId() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"stored\"").build());

        assertThat(repository.create(ID, "record", JELLY)).isPresent();
        assertThat(repository.create(ID, "item", JELLY)).isPresent();
        assertThat(repository.create(ID, "additional-rdf", JELLY)).isPresent();

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, times(3)).putObject(requests.capture(), any(RequestBody.class));
        assertThat(requests.getAllValues()).extracting(request -> request.key())
                .containsExactly(ID + "/record", ID + "/item", ID + "/additional-rdf");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"true,409", "true,412", "false,409", "false,412"})
    void returnsEmptyWhenAConditionalWriteDoesNotSucceed(boolean create, int status) {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(status).build());

        assertThat(create ? repository.create(ID, "record", JELLY)
                : repository.replace(ID, "record", JELLY, "\"previous\""))
                .isEmpty();
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void treatsAMissingReplacementObjectAsAFailedCondition() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build()).build());

        assertThat(repository.replace(ID, "record", JELLY, "*")).isEmpty();
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void doesNotReportAnUnexpectedMissingKeyDuringCreationAsAnExistingRecord() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build()).build());

        assertThatThrownBy(() -> repository.create(ID, "record", JELLY))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void doesNotTreatAMissingBucketAsAWriteConflict() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(NoSuchBucketException.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build());

        assertThatThrownBy(() -> repository.create(ID, "record", JELLY))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void doesNotTreatAnUnqualifiedNotFoundAsAWriteConflict() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThatThrownBy(() -> repository.replace(ID, "record", JELLY, "*"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void mapsNetworkFailureToAStorageFailure() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("backend unreachable"));

        assertThatThrownBy(() -> repository.replace(ID, "record", JELLY, "\"previous\""))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void doesNotTreatAnUnrelatedServiceErrorAsAConflict() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());

        assertThatThrownBy(() -> repository.create(ID, "record", JELLY))
                .isInstanceOf(StorageException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unquoted", "W/\"weak\"", "\"invalid\"quote\"", "\"line\nbreak\"", "\"\u007F\""})
    void rejectsASuccessResponseWithoutAUsableEtag(String etag) {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag(etag).build());

        assertThatThrownBy(() -> repository.create(ID, "record", JELLY))
                .isInstanceOf(StorageException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "abcdefghijklmnopqrstuvwxyz234567"})
    void rejectsInvalidDdbIdentifiersBeforeCallingStorage(String recordId) {
        assertThatThrownBy(() -> repository.create(recordId, "record", JELLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.read(recordId, "record"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "record/item", "/record"})
    void rejectsDocumentNamesThatWouldChangeTheKeyStructure(String name) {
        assertThatThrownBy(() -> repository.create(ID, name, JELLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.read(ID, name))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void neverPerformsAnUnconditionalReplacement(String expectedEtag) {
        assertThatThrownBy(() -> repository.replace(ID, "record", JELLY, expectedEtag))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void readsTheExactBinaryObjectAndItsStorageHeadersInOneRequest() {
        var stored = ResponseBytes.fromByteArray(GetObjectResponse.builder()
                .contentType("application/x-jelly-rdf")
                .contentLength((long) JELLY.length)
                .eTag("\"stored\"")
                .metadata(Map.of("origin", "test"))
                .build(), JELLY);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(stored);

        var result = repository.read(ID, "item").orElseThrow();

        assertThat(result).isSameAs(stored);
        assertThat(result.asByteArray()).containsExactly(JELLY);
        assertThat(result.response().contentType()).isEqualTo("application/x-jelly-rdf");
        assertThat(result.response().eTag()).isEqualTo("\"stored\"");
        assertThat(result.response().metadata()).containsEntry("origin", "test");
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("records");
        assertThat(request.getValue().key()).isEqualTo(ID + "/item");
        verifyNoMoreInteractions(client);
    }

    @Test
    void returnsEmptyForAnExplicitNoSuchKeyReadResponse() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build()).build());

        assertThat(repository.read(ID, "record")).isEmpty();
    }

    @Test
    void returnsEmptyForTheSdkTypedMissingObjectResponse() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThat(repository.read(ID, "record")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"403,AccessDenied", "403,NoSuchKey", "404,NoSuchBucket", "404,", "503,ServiceUnavailable"})
    void doesNotHideStorageFailuresAsAbsentObjects(int status, String errorCode) {
        var failure = S3Exception.builder().statusCode(status)
                .awsErrorDetails(errorCode == null ? null : AwsErrorDetails.builder().errorCode(errorCode).build())
                .build();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.read(ID, "record"))
                .isInstanceOf(StorageException.class).hasCause(failure);
    }

    @Test
    void reportsReadNetworkFailuresWithTheirCause() {
        var failure = SdkClientException.create("backend unreachable");
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.read(ID, "record"))
                .isInstanceOf(StorageException.class).hasCause(failure);
    }

    private static byte[] bytes(RequestBody body) throws Exception {
        try (var stream = body.contentStreamProvider().newStream()) {
            return stream.readAllBytes();
        }
    }

    private static S3StorageProperties properties() {
        return new S3StorageProperties(URI.create("http://127.0.0.1:8333"), "eu-central-1", "records",
                "test-access", "test-secret", true, Duration.ofSeconds(1), Duration.ofSeconds(2), false);
    }
}
