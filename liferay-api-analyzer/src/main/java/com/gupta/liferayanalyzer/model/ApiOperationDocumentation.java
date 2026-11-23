package com.gupta.liferayanalyzer.model;

import java.util.List;

public record ApiOperationDocumentation(

        String path,
        String method,
        String operationId,
        String summary,
        String description,
        List<String> tags,
        List<ParameterDocumentation> parameters,
        RequestBodyDocumentation requestBody,
        List<ResponseDocumentation> responses,
        List<SecurityDocumentation> security

) {

    public record ParameterDocumentation(
            String name,
            String location,
            String description,
            boolean required,
            String schemaType,
            String schemaFormat,
            String schemaReference
    ) {
    }

    public record RequestBodyDocumentation(
            String description,
            boolean required,
            List<ContentDocumentation> content
    ) {
    }

    public record ResponseDocumentation(
            String statusCode,
            String description,
            List<ContentDocumentation> content
    ) {
    }

    public record ContentDocumentation(
            String mediaType,
            String schemaType,
            String schemaFormat,
            String schemaReference
    ) {
    }

    public record SecurityDocumentation(
            String scheme,
            List<String> scopes
    ) {
    }
}