package com.documentation.generator.exception;

import org.springframework.http.HttpStatus;

public class SwaggerUiDiscoveryException extends ApiException {

    public SwaggerUiDiscoveryException(String message) {
        super(HttpStatus.BAD_GATEWAY, message, null);
    }

    public SwaggerUiDiscoveryException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
