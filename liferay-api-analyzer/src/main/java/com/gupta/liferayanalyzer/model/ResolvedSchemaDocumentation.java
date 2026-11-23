package com.gupta.liferayanalyzer.model;

import java.util.List;
import java.util.Map;

public record ResolvedSchemaDocumentation(

        String name,
        String reference,
        String type,
        String format,
        String description,

        boolean circularReference,
        boolean resolved,

        List<String> requiredProperties,

        Map<String, ResolvedSchemaDocumentation> properties,

        ResolvedSchemaDocumentation items,

        List<ResolvedSchemaDocumentation> allOf,
        List<ResolvedSchemaDocumentation> oneOf,
        List<ResolvedSchemaDocumentation> anyOf,

        Boolean additionalPropertiesAllowed,
        ResolvedSchemaDocumentation additionalPropertiesSchema

) {
}