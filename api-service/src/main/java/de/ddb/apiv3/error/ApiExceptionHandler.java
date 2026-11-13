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
package de.ddb.apiv3.error;

import de.ddb.apiv3.storage.StorageException;
import de.ddb.apiv3.rdf.InvalidRdfException;
import de.ddb.apiv3.records.RecordAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

/** RFC 9457 errors, one shape. S3 internals stay in the log, not in the response. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(Exception exception, HttpServletRequest request) {
        LOGGER.debug("Rejected malformed request for {}", request.getRequestURI(), exception);
        String detail = exception instanceof HttpMessageNotReadableException
                ? "The request body is missing or malformed" : "The DDB identifier is invalid";
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail, "INVALID_REQUEST", request,
                List.of(Map.of("detail", detail)));
    }

    @ExceptionHandler(InvalidRdfException.class)
    ResponseEntity<ProblemDetail> handleInvalidRdf(InvalidRdfException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid RDF", exception.getMessage(),
                "INVALID_RDF", request, List.of());
    }

    @ExceptionHandler(RecordAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleRecordAlreadyExists(
            RecordAlreadyExistsException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Record already exists", exception.getMessage(),
                "RECORD_ALREADY_EXISTS", request, List.of());
    }

    @ExceptionHandler({
            ResponseStatusException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            HttpRequestMethodNotSupportedException.class
    })
    ResponseEntity<ProblemDetail> handleHttpError(ErrorResponse exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String title = knownStatus == null ? "HTTP " + status.value() : knownStatus.getReasonPhrase();
        String code = knownStatus == null ? "HTTP_STATUS_" + status.value() : knownStatus.name();
        String detail = exception.getBody().getDetail();
        ResponseEntity<ProblemDetail> response = problem(status, title, detail == null ? title : detail,
                code, request, List.of());
        // Framework errors carry useful protocol headers, notably Allow on 405.
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exception.getHeaders());
        headers.putAll(response.getHeaders());
        return new ResponseEntity<>(response.getBody(), headers, status);
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<ProblemDetail> handleStorageFailure(StorageException exception, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "The persistence backend is temporarily unavailable", "SERVICE_UNAVAILABLE", request, List.of());
        LOGGER.error("Storage failure requestId={} uri={}", response.getHeaders().getFirst("X-Request-Id"),
                request.getRequestURI(), exception);
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatusCode status, String title, String detail, String code,
            HttpServletRequest request, List<Map<String, String>> issues) {
        String requestId = UUID.randomUUID().toString();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        body.setTitle(title);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("requestId", requestId);
        body.setProperty("code", code);
        body.setProperty("errors", issues);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Request-Id", requestId)
                .body("HEAD".equals(request.getMethod()) ? null : body);
    }
}
