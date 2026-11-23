package com.gupta.liferayanalyzer.model;

import java.util.List;

public record ResourceResponseDocumentation(
        String statusCode,
        String description,
        String reference,
        List<ResourceSchemaDocumentation> content
) {}