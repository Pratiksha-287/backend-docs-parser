package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ResourceParameterDocumentation(
        String name,
        String location,
        String description,
        boolean required,
        String schemaType,
        String schemaFormat,
        String schemaReference,
        String reference,

        /* Unexpanded parameter schema used by the compact output.
         * Not serialized by the legacy /resources endpoint. */
        @JsonIgnore CompactSchema compactSchema
) {}
