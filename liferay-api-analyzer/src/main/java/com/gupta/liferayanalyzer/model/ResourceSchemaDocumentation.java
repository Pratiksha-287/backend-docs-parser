package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record ResourceSchemaDocumentation(
        String mediaType,
        String type,
        String format,
        String description,
        String reference,
        List<String> propertyNames,
        List<String> requiredProperties,
        ResolvedSchemaDocumentation resolvedSchema,

        /* Unexpanded schema used by the compact documentation output.
         * Not serialized by the legacy /resources endpoint. */
        @JsonIgnore CompactSchema compactSchema
) {}
