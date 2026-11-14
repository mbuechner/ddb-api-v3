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

package de.ddb.apiv3.error;

import de.ddb.apiv3.storage.StorageException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Spring owns RFC 9457; storage failures reveal only a safe message and a request ID. */
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(StorageException.class)
    @Nullable ResponseEntity<Object> handleStorageFailure(StorageException exception, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The persistence backend is temporarily unavailable");
        var response = handleExceptionInternal(
                exception, body, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
        LOG.atError().setCause(exception)
                .addKeyValue("event.action", "storage_failure")
                .addKeyValue("http.request.id",
                        response == null ? null : response.getHeaders().getFirst("X-Request-Id"))
                .log("Persistence backend unavailable");
        return response;
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String requestId = UUID.randomUUID().toString();
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("requestId", requestId);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.set("X-Request-Id", requestId);
        return super.createResponseEntity(body, responseHeaders, status, request);
    }
}
