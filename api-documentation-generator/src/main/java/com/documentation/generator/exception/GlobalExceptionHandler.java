package com.documentation.generator.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(
            ApiException exception) {

        if (exception.getStatus().is5xxServerError()
                || exception.getCause() != null) {
            log.warn("Request failed: {}", exception.getMessage(),
                    exception);
        }

        return body(exception.getStatus(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception) {

        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map((FieldError error) ->
                        error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return body(
                HttpStatus.BAD_REQUEST,
                message.isBlank() ? "Validation failed" : message
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException exception) {

        return body(
                HttpStatus.BAD_REQUEST,
                "Malformed or missing request body"
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(
            Exception exception) {

        // Spring MVC exceptions (404, 405, 415, missing parameters,
        // upload too large, ...) already know their proper status.
        if (exception instanceof ErrorResponse errorResponse) {

            HttpStatusCode status = errorResponse.getStatusCode();

            return body(
                    HttpStatus.resolve(status.value()) != null
                            ? HttpStatus.valueOf(status.value())
                            : HttpStatus.BAD_REQUEST,
                    errorResponse.getBody().getDetail() != null
                            ? errorResponse.getBody().getDetail()
                            : "Request failed"
            );
        }

        log.error("Unexpected error", exception);

        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred"
        );
    }

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status,
            String message) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now());
        payload.put("status", status.value());
        payload.put("error", message);

        return ResponseEntity.status(status).body(payload);
    }
}
