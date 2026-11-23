package com.documentation.generator.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for errors that should be reported to the API client.
 * Carries the HTTP status that the {@link GlobalExceptionHandler} will return.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(String message) {
        this(HttpStatus.BAD_REQUEST, message, null);
    }

    public ApiException(String message, Throwable cause) {
        this(HttpStatus.BAD_REQUEST, message, cause);
    }

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
