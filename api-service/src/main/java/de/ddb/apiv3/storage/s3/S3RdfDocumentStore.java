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

import de.ddb.apiv3.storage.StorageException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** One Jelly document, one object: {@code DDB-ID/documentName}. S3 performs the compare-and-swap. */
@Repository
public final class S3RdfDocumentStore {

    private static final Pattern RECORD_ID = Pattern.compile("[A-Z2-7]{32}");
    private static final Pattern DOCUMENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern STRONG_ETAG = Pattern.compile("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"");
    private static final String JELLY_CONTENT_TYPE = "application/x-jelly-rdf";

    private final S3Client client;
    private final String bucket;

    public S3RdfDocumentStore(S3Client client, S3StorageProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    public Optional<String> create(String recordId, String documentName, byte[] jelly) {
        return put(PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(recordId, documentName))
                .contentType(JELLY_CONTENT_TYPE)
                .ifNoneMatch("*")
                .build(), jelly);
    }

    public Optional<String> replace(String recordId, String documentName, byte[] jelly, String expectedEtag) {
        if (expectedEtag == null || expectedEtag.isBlank()) {
            throw new IllegalArgumentException("Replacing an RDF document requires an If-Match condition");
        }
        return put(PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(recordId, documentName))
                .contentType(JELLY_CONTENT_TYPE)
                .ifMatch(expectedEtag)
                .build(), jelly);
    }

    public Optional<ResponseBytes<GetObjectResponse>> read(String recordId, String documentName) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(recordId, documentName))
                .build();
        try {
            return Optional.of(client.getObjectAsBytes(request));
        } catch (S3Exception exception) {
            if (missingObject(exception)) {
                return Optional.empty();
            }
            throw unavailable(exception);
        } catch (SdkException exception) {
            throw unavailable(exception);
        }
    }

    private Optional<String> put(PutObjectRequest request, byte[] jelly) {
        try {
            String etag = client.putObject(request, RequestBody.fromBytes(jelly)).eTag();
            if (etag == null || !STRONG_ETAG.matcher(etag).matches()) {
                throw new StorageException("The S3 write response did not contain a valid strong ETag");
            }
            return Optional.of(etag);
        } catch (S3Exception exception) {
            // SeaweedFS also reports a failed If-Match on an absent object as NoSuchKey.
            // A missing bucket or an unqualified 404 is an infrastructure failure instead.
            if (exception.statusCode() == 412 || exception.statusCode() == 409
                    || (request.ifMatch() != null && missingObject(exception))) {
                return Optional.empty();
            }
            throw unavailable(exception);
        } catch (SdkException exception) {
            throw unavailable(exception);
        }
    }

    private static boolean missingObject(S3Exception exception) {
        return exception.statusCode() == 404
                && (exception instanceof NoSuchKeyException
                || (exception.awsErrorDetails() != null
                && "NoSuchKey".equals(exception.awsErrorDetails().errorCode())));
    }

    private static String objectKey(String recordId, String documentName) {
        if (recordId == null || !RECORD_ID.matcher(recordId).matches()) {
            throw new IllegalArgumentException("A DDB identifier must contain 32 characters from A-Z and 2-7");
        }
        if (documentName == null || !DOCUMENT_NAME.matcher(documentName).matches()) {
            throw new IllegalArgumentException("An RDF document name must be a single 1-64 character name");
        }
        return recordId + "/" + documentName;
    }

    private static StorageException unavailable(SdkException exception) {
        return new StorageException("The S3 RDF storage is unavailable", exception);
    }
}
