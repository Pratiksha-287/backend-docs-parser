package com.gupta.liferayanalyzer.model;

import java.util.List;

public record ParsedOpenApiResponse(

        String title,

        String description,

        String openApiVersion,

        int pathCount,

        int operationCount,

        int schemaCount,

        int tagCount,

        List<String> servers

) {
}