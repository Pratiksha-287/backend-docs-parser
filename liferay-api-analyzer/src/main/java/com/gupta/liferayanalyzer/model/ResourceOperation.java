package com.gupta.liferayanalyzer.model;

import java.util.List;
import java.util.Map;

public record ResourceOperation(
        String path,
        String httpMethod,
        String operationId,
        String summary,
        String description,

        List<ResourceParameterDocumentation> parameters,

        ResourceRequestBodyDocumentation requestBody,

        List<ResourceResponseDocumentation> responses,

        List<Map<String, List<String>>> security
) {}