
package com.gupta.liferayanalyzer.normalizer;

import com.gupta.liferayanalyzer.model.CompactContent;
import com.gupta.liferayanalyzer.model.CompactOperation;
import com.gupta.liferayanalyzer.model.CompactParameter;
import com.gupta.liferayanalyzer.model.CompactRelationship;
import com.gupta.liferayanalyzer.model.CompactRequestBody;
import com.gupta.liferayanalyzer.model.CompactResponse;
import com.gupta.liferayanalyzer.model.CompactSchema;
import com.gupta.liferayanalyzer.model.LiferayResource;
import com.gupta.liferayanalyzer.model.ResourceOperation;
import com.gupta.liferayanalyzer.model.ResourceParameterDocumentation;
import com.gupta.liferayanalyzer.model.ResourceRequestBodyDocumentation;
import com.gupta.liferayanalyzer.model.ResourceResponseDocumentation;
import com.gupta.liferayanalyzer.model.ResourceSchemaDocumentation;
import com.gupta.liferayanalyzer.model.SchemaRelationship;
import com.gupta.liferayanalyzer.parser.CompactSchemaConverter;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the compact documentation (format 2.0).
 *
 * Size reduction strategy (nothing of substance is dropped):
 *  1. Schemas are written once in a top-level dictionary and referenced by
 *     name, instead of being expanded recursively inside every operation.
 *  2. Parameter definitions are de-duplicated into a shared dictionary.
 *  3. Media types with the same schema (json/xml) are grouped.
 *  4. Global security is stated once; operations only list overrides.
 *  5. Null / empty / false members are omitted.
 *  6. Relationships are grouped by source schema, without prose text.
 */
@Component
public class DocumentationNormalizer {

    private static final String DOCUMENT_VERSION = "2.0";

    private final CompactSchemaConverter schemaConverter;

    public DocumentationNormalizer(CompactSchemaConverter schemaConverter) {
        this.schemaConverter = schemaConverter;
    }

    public NormalizedDocumentation normalize(
            OpenAPI openAPI,
            List<LiferayResource> resources,
            List<SchemaRelationship> relationships) {

        if (openAPI == null) {
            throw new IllegalArgumentException(
                    "OpenAPI document cannot be null"
            );
        }

        List<LiferayResource> safeResources =
                resources == null ? List.of() : resources;

        List<SchemaRelationship> safeRelationships =
                relationships == null ? List.of() : relationships;

        List<Map<String, List<String>>> globalSecurity =
                toSecurityList(openAPI.getSecurity());

        ParameterRegistry parameterRegistry = new ParameterRegistry();

        List<NormalizedResource> normalizedResources =
                normalizeResources(
                        safeResources,
                        globalSecurity,
                        parameterRegistry
                );

        Map<String, CompactSchema> schemas = buildSchemas(openAPI);

        return new NormalizedDocumentation(
                DOCUMENT_VERSION,
                Instant.now(),
                extractApiMetadata(openAPI, globalSecurity),
                safeResources.size(),
                countOperations(openAPI),
                schemas.size(),
                safeRelationships.size(),
                normalizedResources,
                parameterRegistry.definitions(),
                schemas,
                groupRelationships(safeRelationships)
        );
    }

    // ---------------------------------------------------------
    // API METADATA
    // ---------------------------------------------------------

    private NormalizedApiMetadata extractApiMetadata(
            OpenAPI openAPI,
            List<Map<String, List<String>>> globalSecurity) {

        String title = null;
        String description = null;
        String version = null;

        if (openAPI.getInfo() != null) {

            title = openAPI.getInfo().getTitle();
            description = openAPI.getInfo().getDescription();
            version = openAPI.getInfo().getVersion();
        }

        List<String> servers =
                openAPI.getServers() == null
                        ? List.of()
                        : openAPI.getServers()
                                .stream()
                                .filter(server -> server != null)
                                .map(server -> server.getUrl())
                                .filter(url -> url != null && !url.isBlank())
                                .toList();

        return new NormalizedApiMetadata(
                title,
                description,
                version,
                openAPI.getOpenapi(),
                servers,
                globalSecurity
        );
    }

    // ---------------------------------------------------------
    // SCHEMAS (each written exactly once)
    // ---------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private Map<String, CompactSchema> buildSchemas(OpenAPI openAPI) {

        Map<String, CompactSchema> result = new TreeMap<>();

        if (openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null) {
            return result;
        }

        Map<String, Schema> schemas =
                openAPI.getComponents().getSchemas();

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {

            if (entry.getValue() == null) {
                continue;
            }

            result.put(
                    entry.getKey(),
                    schemaConverter.convert((Schema<?>) entry.getValue())
            );
        }

        return result;
    }

    // ---------------------------------------------------------
    // RESOURCE NORMALIZATION
    // ---------------------------------------------------------

    private List<NormalizedResource> normalizeResources(
            List<LiferayResource> resources,
            List<Map<String, List<String>>> globalSecurity,
            ParameterRegistry parameterRegistry) {

        List<NormalizedResource> result = new ArrayList<>();

        for (LiferayResource resource : resources) {
            result.add(
                    normalizeResource(
                            resource,
                            globalSecurity,
                            parameterRegistry
                    )
            );
        }

        return result;
    }

    private NormalizedResource normalizeResource(
            LiferayResource resource,
            List<Map<String, List<String>>> globalSecurity,
            ParameterRegistry parameterRegistry) {

        List<ResourceOperation> operations =
                resource.operations() == null
                        ? List.of()
                        : resource.operations();

        List<CompactOperation> compactOperations = new ArrayList<>();

        for (ResourceOperation operation : operations) {
            compactOperations.add(
                    normalizeOperation(
                            operation,
                            globalSecurity,
                            parameterRegistry
                    )
            );
        }

        boolean classified = !"Unclassified".equals(resource.apiName());

        return new NormalizedResource(
                resource.apiName(),
                resource.apiVersion(),
                resource.resourceName(),
                classified ? null : resource.sourceTag(),
                compactOperations.size(),
                compactOperations
        );
    }

    private CompactOperation normalizeOperation(
            ResourceOperation operation,
            List<Map<String, List<String>>> globalSecurity,
            ParameterRegistry parameterRegistry) {

        List<String> parameterKeys = new ArrayList<>();

        if (operation.parameters() != null) {
            for (ResourceParameterDocumentation parameter
                    : operation.parameters()) {

                parameterKeys.add(
                        parameterRegistry.register(
                                toCompactParameter(parameter)
                        )
                );
            }
        }

        return new CompactOperation(
                operation.operationId(),
                operation.httpMethod(),
                operation.path(),
                operation.summary(),
                operation.description(),
                parameterKeys,
                toCompactRequestBody(operation.requestBody()),
                toCompactResponses(operation.responses()),
                securityOverride(operation.security(), globalSecurity)
        );
    }

    private CompactParameter toCompactParameter(
            ResourceParameterDocumentation parameter) {

        return new CompactParameter(
                parameter.name(),
                parameter.location(),
                parameter.description(),
                parameter.required() ? Boolean.TRUE : null,
                parameter.compactSchema()
        );
    }

    private CompactRequestBody toCompactRequestBody(
            ResourceRequestBodyDocumentation requestBody) {

        if (requestBody == null) {
            return null;
        }

        return new CompactRequestBody(
                requestBody.description(),
                requestBody.required() ? Boolean.TRUE : null,
                groupContent(requestBody.content())
        );
    }

    private Map<String, CompactResponse> toCompactResponses(
            List<ResourceResponseDocumentation> responses) {

        if (responses == null || responses.isEmpty()) {
            return null;
        }

        Map<String, CompactResponse> result = new LinkedHashMap<>();

        for (ResourceResponseDocumentation response : responses) {

            result.put(
                    response.statusCode(),
                    new CompactResponse(
                            response.description(),
                            groupContent(response.content())
                    )
            );
        }

        return result;
    }

    /**
     * Groups media types that carry an identical schema, so the very
     * common json + xml pair is written once.
     */
    private List<CompactContent> groupContent(
            List<ResourceSchemaDocumentation> content) {

        if (content == null || content.isEmpty()) {
            return null;
        }

        Map<CompactSchema, List<String>> groups = new LinkedHashMap<>();

        for (ResourceSchemaDocumentation item : content) {
            groups.computeIfAbsent(
                    item.compactSchema(),
                    key -> new ArrayList<>()
            ).add(item.mediaType());
        }

        List<CompactContent> result = new ArrayList<>();

        groups.forEach((schema, mediaTypes) ->
                result.add(new CompactContent(mediaTypes, schema))
        );

        return result;
    }

    // ---------------------------------------------------------
    // SECURITY
    // ---------------------------------------------------------

    private List<Map<String, List<String>>> toSecurityList(
            List<SecurityRequirement> requirements) {

        if (requirements == null) {
            return List.of();
        }

        List<Map<String, List<String>>> result = new ArrayList<>();

        for (SecurityRequirement requirement : requirements) {
            result.add(new LinkedHashMap<>(requirement));
        }

        return result;
    }

    /**
     * The analyzer copies the global security into every operation.
     * Only keep it when it is different from the global one.
     */
    private List<Map<String, List<String>>> securityOverride(
            List<Map<String, List<String>>> operationSecurity,
            List<Map<String, List<String>>> globalSecurity) {

        if (operationSecurity == null
                || operationSecurity.equals(globalSecurity)) {
            return null;
        }

        if (operationSecurity.isEmpty()) {
            // Explicitly public operation: "[{}]" is the OpenAPI idiom
            // for "no authentication required".
            return List.of(Map.of());
        }

        return operationSecurity;
    }

    // ---------------------------------------------------------
    // RELATIONSHIPS
    // ---------------------------------------------------------

    private Map<String, List<CompactRelationship>> groupRelationships(
            List<SchemaRelationship> relationships) {

        Map<String, List<CompactRelationship>> result = new TreeMap<>();

        for (SchemaRelationship relationship : relationships) {

            result.computeIfAbsent(
                    relationship.sourceSchema(),
                    key -> new ArrayList<>()
            ).add(new CompactRelationship(
                    relationship.sourceProperty(),
                    relationship.targetSchema(),
                    relationship.relationshipType(),
                    relationship.evidence(),
                    relationship.confidence()
            ));
        }

        return result;
    }

    // ---------------------------------------------------------
    // OPERATION COUNT
    // ---------------------------------------------------------

    private int countOperations(OpenAPI openAPI) {

        if (openAPI.getPaths() == null) {
            return 0;
        }

        return openAPI.getPaths()
                .values()
                .stream()
                .filter(pathItem -> pathItem != null)
                .mapToInt(pathItem -> {

                    Map<PathItem.HttpMethod,
                            io.swagger.v3.oas.models.Operation> operations =
                            pathItem.readOperationsMap();

                    return operations == null
                            ? 0
                            : operations.size();
                })
                .sum();
    }

    // ---------------------------------------------------------
    // PARAMETER DE-DUPLICATION
    // ---------------------------------------------------------

    /**
     * Stores each distinct parameter definition once.
     * Key format: "<in>.<name>", with "~2", "~3", ... when a different
     * definition uses the same in/name pair.
     */
    private static final class ParameterRegistry {

        private final Map<String, CompactParameter> definitions =
                new TreeMap<>();

        private final Map<CompactParameter, String> keysByDefinition =
                new HashMap<>();

        String register(CompactParameter parameter) {

            String existingKey = keysByDefinition.get(parameter);

            if (existingKey != null) {
                return existingKey;
            }

            String baseKey = parameter.in() + "." + parameter.name();
            String key = baseKey;
            int counter = 2;

            while (definitions.containsKey(key)) {
                key = baseKey + "~" + counter++;
            }

            definitions.put(key, parameter);
            keysByDefinition.put(parameter, key);

            return key;
        }

        Map<String, CompactParameter> definitions() {
            return definitions;
        }
    }
}
