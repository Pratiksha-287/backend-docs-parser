package com.gupta.liferayanalyzer.model;

import java.util.List;

public record ResourceRequestBodyDocumentation(
        String description,
        boolean required,
        String reference,
        List<ResourceSchemaDocumentation> content
) {}