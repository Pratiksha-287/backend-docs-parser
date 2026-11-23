
package com.documentation.generator.model;

import jakarta.validation.constraints.NotBlank;

public record SourceAuthentication(

        @NotBlank(message = "Authentication type is required")
        String type,

        @NotBlank(message = "Bearer token is required")
        String token

) {
}
