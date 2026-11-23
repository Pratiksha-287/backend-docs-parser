
package com.documentation.generator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeliveryRequest(

        @NotBlank
        String documentId,

        @NotNull
        Boolean regenerate

) {
}