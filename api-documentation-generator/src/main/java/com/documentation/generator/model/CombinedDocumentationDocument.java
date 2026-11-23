package com.documentation.generator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CombinedDocumentationDocument(

        String contractVersion,

        Metadata documentMetadata,

        DocumentationDocument.Statistics statistics,

        List<ApiDocumentation> apis,

        List<FailedApi> failedApis

) {

    public record Metadata(
            String projectName,
            String sourceUrl,
            String sourceType,
            int totalApis,
            int documentedApis,
            int failedApis,
            JsonNode conventions,
            Instant generatedAt
    ) {}

    public record ApiDocumentation(
            String name,
            String specificationUrl,
            DocumentationDocument documentation
    ) {}

    /** An API that was discovered but could not be documented. */
    public record FailedApi(
            String name,
            String specificationUrl,
            String error
    ) {}
}
