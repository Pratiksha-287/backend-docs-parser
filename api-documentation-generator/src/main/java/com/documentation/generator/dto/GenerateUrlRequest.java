
package com.documentation.generator.dto;

import com.documentation.generator.model.SourceAuthentication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GenerateUrlRequest(

        @NotBlank
        @Pattern(
            regexp = "^https?://.+",
            message = "Only HTTP and HTTPS URLs are supported"
        )
        String sourceUrl,

        @NotBlank
        @Size(max = 150)
        String projectName,

        @Valid
        SourceAuthentication authentication

) {
}
