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

import de.ddb.apiv3.generated.model.Problem;
import de.ddb.apiv3.generated.model.ProblemErrorsInner;
import de.ddb.apiv3.record.write.InvalidRdfException;
import de.ddb.apiv3.record.write.RecordAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Converts transport and domain failures to the contract's {@code application/problem+json}
 * representation.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Problem> handleNotFound(NoSuchElementException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Record not found", exception.getMessage(),
                "RECORD_NOT_FOUND", request, List.of());
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<Problem> handleBadRequest(Exception exception, HttpServletRequest request) {
        // Invalid input is expected operationally, so retain diagnostic detail only at debug level.
        LOGGER.debug("Rejected malformed request for {}: {}",
                request.getRequestURI(), exception.getMessage(), exception);
        ProblemErrorsInner issue = new ProblemErrorsInner(readableDetail(exception));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Request validation failed",
                "INVALID_REQUEST", request, List.of(issue));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Problem> handlePayloadTooLarge(MaxUploadSizeExceededException exception,
                                                   HttpServletRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Content too large", exception.getMessage(),
                "CONTENT_TOO_LARGE", request, List.of());
    }

    @ExceptionHandler(InvalidRdfException.class)
    ResponseEntity<Problem> handleInvalidRdf(InvalidRdfException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid RDF", exception.getMessage(),
                "INVALID_RDF", request, List.of());
    }

    @ExceptionHandler(RecordAlreadyExistsException.class)
    ResponseEntity<Problem> handleRecordAlreadyExists(
            RecordAlreadyExistsException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Record already exists", exception.getMessage(),
                "RECORD_ALREADY_EXISTS", request, List.of());
    }

    /** Maps explicit endpoint states such as a tombstone (410) or failed negotiation (406). */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Problem> handleResponseStatus(ResponseStatusException exception,
                                                  HttpServletRequest request) {
        HttpStatusCode statusCode = exception.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = switch (statusCode.value()) {
            case 410 -> "RECORD_GONE";
            case 406 -> "NOT_ACCEPTABLE";
            default -> status == null ? "HTTP_STATUS_" + statusCode.value() : status.name();
        };
        String title = status == null ? "HTTP " + statusCode.value() : status.getReasonPhrase();
        String detail = exception.getReason() == null ? title : exception.getReason();
        return problem(statusCode, title, detail, code, request, List.of());
    }

    /**
     * Hides database implementation details and returns the contract's retryable availability
     * response when the external persistence service cannot satisfy a request.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Problem> handleDataAccessFailure(DataAccessException exception,
                                                     HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        LOGGER.error("Persistence failure for requestId={} uri={}",
                requestId, request.getRequestURI(), exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "The persistence backend is temporarily unavailable",
                "SERVICE_UNAVAILABLE", request, List.of(), requestId);
    }

    private ResponseEntity<Problem> problem(
            HttpStatusCode status,
            String title,
            String detail,
            String code,
            HttpServletRequest request,
            List<ProblemErrorsInner> issues) {
        return problem(status, title, detail, code, request, issues, UUID.randomUUID().toString());
    }

    private ResponseEntity<Problem> problem(
            HttpStatusCode status,
            String title,
            String detail,
            String code,
            HttpServletRequest request,
            List<ProblemErrorsInner> issues,
            String requestId) {
        Problem body = new Problem("/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title, status.value(), requestId)
                .detail(detail)
                .instance(request.getRequestURI())
                .code(code)
                .errors(issues);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(PROBLEM_JSON);
        headers.set("X-Request-Id", requestId);
        // HEAD has the same status and headers as GET, but never transfers a response body. This
        // also applies to Problem Details produced by exception handling.
        Problem responseBody = "HEAD".equals(request.getMethod()) ? null : body;
        return new ResponseEntity<>(responseBody, headers, status);
    }

    private static String readableDetail(Exception exception) {
        if (exception instanceof HttpMessageNotReadableException) {
            return "The request body is malformed";
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            return "Parameter '%s' has an invalid value".formatted(mismatch.getName());
        }
        return exception.getMessage() == null ? "Invalid request" : exception.getMessage();
    }
}
