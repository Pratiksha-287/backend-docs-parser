package com.documentation.generator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Compact documentation contract (v2.0).
 *
 * Empty / null values are never written, and the original specification is
 * no longer embedded (everything important is already normalised here).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record DocumentationDocument(

        String contractVersion,

        Metadata documentMetadata,

        Statistics statistics,

        List<Module> modules,

        JsonNode schemas,

        JsonNode securitySchemes,

        List<Relationship> relationships,

        List<WorkflowCandidate> workflows,

        List<String> parserMessages,

        JsonNode originalSpecification

) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Metadata(
            String projectName,
            String sourceUrl,
            String sourceType,
            String sourceSpecificationVersion,
            String normalizedSpecificationVersion,
            String title,
            String description,
            String apiVersion,
            List<String> servers,
            JsonNode conventions,
            Instant generatedAt
    ) {}

    public record Statistics(
            int totalEndpoints,
            int totalSchemas,
            int totalModules,
            int totalRelationships
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Module(
            String name,
            String description,
            Boolean descriptionInferred,
            List<String> schemas,
            List<JsonNode> endpoints
    ) {}

    /**
     * A directed link between two documented elements.
     *
     * from / to : "GET /users/{id}" for endpoints, "User" for schemas
     * type      : REQUEST_BODY, RESPONSE, PARAMETER, HAS_ONE, HAS_MANY,
     *             EXTENDS, ONE_OF, ANY_OF
     * via       : field name, status code or parameter name carrying the link
     * required  : true when the link is mandatory
     * description: one human readable sentence
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Relationship(
            String from,
            String to,
            String type,
            String via,
            Boolean required,
            String description
    ) {}

    public record WorkflowCandidate(
            String name,
            String description,
            List<String> operations,
            String classification,
            String confidence
    ) {}
}
