
package com.gupta.liferayanalyzer.analyzer;

import com.gupta.liferayanalyzer.model.LiferayResource;
import com.gupta.liferayanalyzer.model.ResourceOperation;
import com.gupta.liferayanalyzer.model.ResourceParameterDocumentation;
import com.gupta.liferayanalyzer.model.ResourceRequestBodyDocumentation;
import com.gupta.liferayanalyzer.model.ResourceResponseDocumentation;
import com.gupta.liferayanalyzer.model.ResourceSchemaDocumentation;
import com.gupta.liferayanalyzer.model.ResolvedSchemaDocumentation;
import com.gupta.liferayanalyzer.parser.CompactSchemaConverter;
import com.gupta.liferayanalyzer.parser.OpenApiSchemaResolver;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LiferayResourceAnalyzer {

    private static final Pattern TAG_PATTERN = Pattern.compile(
            "^(.+)\\.(v\\d+(?:\\.\\d+)*)\\.(.+)$"
    );

    private static final String COMPONENT_PREFIX =
            "#/components/";

    private final OpenApiSchemaResolver schemaResolver;
    private final CompactSchemaConverter compactConverter;

    public LiferayResourceAnalyzer(
            OpenApiSchemaResolver schemaResolver) {
        this(schemaResolver, new CompactSchemaConverter());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LiferayResourceAnalyzer(
            OpenApiSchemaResolver schemaResolver,
            CompactSchemaConverter compactConverter) {
        this.schemaResolver = schemaResolver;
        this.compactConverter = compactConverter;
    }

    // =========================================================
    // MAIN ANALYSIS
    // =========================================================

    /**
     * Legacy behaviour: every request/response schema is fully expanded
     * (very large for big Liferay installations).
     */
    public List<LiferayResource> analyze(OpenAPI openAPI) {
        return analyze(openAPI, true);
    }

    /**
     * @param expandSchemas when false, the fully expanded "resolvedSchema"
     *        trees are not built (the compact documentation output
     *        references schemas by name instead).
     */
    public List<LiferayResource> analyze(
            OpenAPI openAPI,
            boolean expandSchemas) {

        if (openAPI == null || openAPI.getPaths() == null) {
            return List.of();
        }

        Map<ResourceKey, ResourceAccumulator> resourceMap =
                new LinkedHashMap<>();

        openAPI.getPaths().forEach((path, pathItem) -> {

            if (pathItem == null) {
                return;
            }

            Map<PathItem.HttpMethod, Operation> operations =
                    pathItem.readOperationsMap();

            if (operations == null) {
                return;
            }

            operations.forEach((httpMethod, operation) -> {

                if (operation == null) {
                    return;
                }

                List<String> tags = operation.getTags();

                if (tags != null && !tags.isEmpty()) {

                    tags.stream()
                            .filter(tag -> tag != null && !tag.isBlank())
                            .distinct()
                            .forEach(tag -> addOperation(
                                    openAPI,
                                    resourceMap,
                                    tag,
                                    path,
                                    pathItem,
                                    httpMethod,
                                    operation,
                                    expandSchemas
                            ));

                } else {

                    addOperation(
                            openAPI,
                            resourceMap,
                            null,
                            path,
                            pathItem,
                            httpMethod,
                            operation,
                            expandSchemas
                    );
                }
            });
        });

        return resourceMap.values()
                .stream()
                .map(ResourceAccumulator::toResource)
                .sorted(
                        Comparator.comparing(LiferayResource::apiName)
                                .thenComparing(LiferayResource::apiVersion)
                                .thenComparing(LiferayResource::resourceName)
                )
                .toList();
    }

    private void addOperation(
            OpenAPI openAPI,
            Map<ResourceKey, ResourceAccumulator> resourceMap,
            String tag,
            String path,
            PathItem pathItem,
            PathItem.HttpMethod httpMethod,
            Operation operation,
            boolean expandSchemas) {

        ResourceKey key = parseResourceKey(tag, path);

        ResourceAccumulator accumulator =
                resourceMap.computeIfAbsent(
                        key,
                        ResourceAccumulator::new
                );

        accumulator.addOperation(
                extractOperation(
                        openAPI,
                        path,
                        pathItem,
                        httpMethod,
                        operation,
                        expandSchemas
                )
        );
    }

    // =========================================================
    // OPERATION EXTRACTION
    // =========================================================

    private ResourceOperation extractOperation(
            OpenAPI openAPI,
            String path,
            PathItem pathItem,
            PathItem.HttpMethod httpMethod,
            Operation operation,
            boolean expandSchemas) {

        return new ResourceOperation(
                path,
                httpMethod.name(),
                operation.getOperationId(),
                operation.getSummary(),
                operation.getDescription(),

                extractParameters(
                        openAPI,
                        pathItem,
                        operation
                ),

                extractRequestBody(
                        openAPI,
                        operation,
                        expandSchemas
                ),

                extractResponses(
                        openAPI,
                        operation,
                        expandSchemas
                ),

                extractSecurity(
                        openAPI,
                        operation
                )
        );
    }

    // =========================================================
    // 1. PARAMETERS
    // =========================================================

    private List<ResourceParameterDocumentation> extractParameters(
            OpenAPI openAPI,
            PathItem pathItem,
            Operation operation) {

        Map<String, Parameter> mergedParameters =
                new LinkedHashMap<>();

        // Path-level parameters are added first.
        addParameters(
                openAPI,
                mergedParameters,
                pathItem.getParameters()
        );

        // Operation-level parameters override path-level ones.
        addParameters(
                openAPI,
                mergedParameters,
                operation.getParameters()
        );

        return mergedParameters.values()
                .stream()
                .map(parameter ->
                        extractParameter(openAPI, parameter)
                )
                .toList();
    }

    private void addParameters(
            OpenAPI openAPI,
            Map<String, Parameter> target,
            List<Parameter> parameters) {

        if (parameters == null) {
            return;
        }

        for (Parameter original : parameters) {

            if (original == null) {
                continue;
            }

            Parameter resolved = resolveParameter(
                    openAPI,
                    original
            );

            String key = parameterKey(
                    resolved,
                    original
            );

            target.put(key, resolved);
        }
    }

    private String parameterKey(
            Parameter resolved,
            Parameter original) {

        if (resolved.getName() != null
                && resolved.getIn() != null) {

            return resolved.getIn()
                    + ":"
                    + resolved.getName();
        }

        if (original.get$ref() != null) {
            return "ref:" + original.get$ref();
        }

        return "unknown:" + System.identityHashCode(original);
    }

    private ResourceParameterDocumentation extractParameter(
            OpenAPI openAPI,
            Parameter original) {

        Parameter parameter = resolveParameter(
                openAPI,
                original
        );

        Schema<?> schema = parameter.getSchema();

        return new ResourceParameterDocumentation(
                parameter.getName(),
                parameter.getIn(),
                parameter.getDescription(),
                Boolean.TRUE.equals(parameter.getRequired()),
                schema == null ? null : schema.getType(),
                schema == null ? null : schema.getFormat(),
                schema == null ? null : schema.get$ref(),
                original.get$ref(),
                compactConverter.convert(schema)
        );
    }

    private Parameter resolveParameter(
            OpenAPI openAPI,
            Parameter parameter) {

        if (parameter == null || openAPI.getComponents() == null) {
            return parameter;
        }

        Set<String> visited = new LinkedHashSet<>();

        Parameter current = parameter;

        while (current.get$ref() != null) {

            String reference = current.get$ref();

            if (!visited.add(reference)) {
                break;
            }

            String componentName = extractComponentName(
                    reference,
                    "parameters"
            );

            if (componentName == null) {
                break;
            }

            Parameter component =
                    openAPI.getComponents()
                            .getParameters()
                            .get(componentName);

            if (component == null) {
                break;
            }

            current = component;
        }

        return current;
    }

    // =========================================================
    // 2. REQUEST BODY REFERENCES
    // =========================================================

    private ResourceRequestBodyDocumentation extractRequestBody(
            OpenAPI openAPI,
            Operation operation,
            boolean expandSchemas) {

        RequestBody original = operation.getRequestBody();

        if (original == null) {
            return null;
        }

        RequestBody resolved = resolveRequestBody(
                openAPI,
                original
        );

        return new ResourceRequestBodyDocumentation(
                resolved.getDescription(),
                Boolean.TRUE.equals(resolved.getRequired()),
                original.get$ref(),
                extractContent(
                        openAPI,
                        resolved.getContent(),
                        expandSchemas
                )
        );
    }

    private RequestBody resolveRequestBody(
            OpenAPI openAPI,
            RequestBody requestBody) {

        if (requestBody == null
                || openAPI.getComponents() == null) {
            return requestBody;
        }

        Set<String> visited = new LinkedHashSet<>();

        RequestBody current = requestBody;

        while (current.get$ref() != null) {

            String reference = current.get$ref();

            if (!visited.add(reference)) {
                break;
            }

            String componentName = extractComponentName(
                    reference,
                    "requestBodies"
            );

            if (componentName == null) {
                break;
            }

            RequestBody component =
                    openAPI.getComponents()
                            .getRequestBodies()
                            .get(componentName);

            if (component == null) {
                break;
            }

            current = component;
        }

        return current;
    }

    // =========================================================
    // 3. RESPONSE REFERENCES
    // =========================================================

    private List<ResourceResponseDocumentation> extractResponses(
            OpenAPI openAPI,
            Operation operation,
            boolean expandSchemas) {

        if (operation.getResponses() == null) {
            return List.of();
        }

        List<ResourceResponseDocumentation> result =
                new ArrayList<>();

        operation.getResponses().forEach((statusCode, original) -> {

            if (original == null) {
                return;
            }

            ApiResponse resolved = resolveResponse(
                    openAPI,
                    original
            );

            result.add(
                    new ResourceResponseDocumentation(
                            statusCode,
                            resolved.getDescription(),
                            original.get$ref(),
                            extractContent(
                                    openAPI,
                                    resolved.getContent(),
                                    expandSchemas
                            )
                    )
            );
        });

        return result;
    }

    private ApiResponse resolveResponse(
            OpenAPI openAPI,
            ApiResponse response) {

        if (response == null
                || openAPI.getComponents() == null) {
            return response;
        }

        Set<String> visited = new LinkedHashSet<>();

        ApiResponse current = response;

        while (current.get$ref() != null) {

            String reference = current.get$ref();

            if (!visited.add(reference)) {
                break;
            }

            String componentName = extractComponentName(
                    reference,
                    "responses"
            );

            if (componentName == null) {
                break;
            }

            ApiResponse component =
                    openAPI.getComponents()
                            .getResponses()
                            .get(componentName);

            if (component == null) {
                break;
            }

            current = component;
        }

        return current;
    }

    // =========================================================
    // 4. CONTENT AND SCHEMA EXTRACTION
    // =========================================================

    private List<ResourceSchemaDocumentation> extractContent(
            OpenAPI openAPI,
            Content content,
            boolean expandSchemas) {

        if (content == null || content.isEmpty()) {
            return List.of();
        }

        List<ResourceSchemaDocumentation> result =
                new ArrayList<>();

        content.forEach((mediaType, mediaTypeObject) -> {

            if (mediaTypeObject == null) {
                return;
            }

            Schema<?> schema = mediaTypeObject.getSchema();

            if (schema == null) {

                result.add(
                        new ResourceSchemaDocumentation(
                                mediaType,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null
                        )
                );

                return;
            }

            result.add(
                    extractSchema(
                            openAPI,
                            mediaType,
                            schema,
                            expandSchemas
                    )
            );
        });

        return result;
    }

    private ResourceSchemaDocumentation extractSchema(
            OpenAPI openAPI,
            String mediaType,
            Schema<?> schema,
            boolean expandSchemas) {

        String reference = schema.get$ref();

        List<String> propertyNames = List.of();

        if (schema.getProperties() != null) {

            propertyNames = schema.getProperties()
                    .keySet()
                    .stream()
                    .sorted()
                    .toList();
        }

        List<String> requiredProperties =
                schema.getRequired() == null
                        ? List.of()
                        : schema.getRequired();

        ResolvedSchemaDocumentation resolvedSchema = null;

        if (expandSchemas && reference != null) {

            String schemaName = extractComponentName(
                    reference,
                    "schemas"
            );

            if (schemaName != null) {

                resolvedSchema = schemaResolver.resolve(
                        openAPI,
                        schemaName
                );
            }
        }

        return new ResourceSchemaDocumentation(
                mediaType,
                schema.getType(),
                schema.getFormat(),
                schema.getDescription(),
                reference,
                propertyNames,
                requiredProperties,
                resolvedSchema,
                compactConverter.convert(schema)
        );
    }

    // =========================================================
    // 5. SECURITY
    // =========================================================

    private List<Map<String, List<String>>> extractSecurity(
            OpenAPI openAPI,
            Operation operation) {

        List<SecurityRequirement> requirements =
                operation.getSecurity();

        // If operation security is not defined,
        // inherit the global OpenAPI security requirements.
        if (requirements == null && openAPI != null) {
            requirements = openAPI.getSecurity();
        }

        if (requirements == null) {
            return List.of();
        }

        return requirements.stream()
                .<Map<String, List<String>>>map(
                        requirement -> new LinkedHashMap<>(requirement)
                )
                .toList();
    }

    // =========================================================
    // REFERENCE UTILITIES
    // =========================================================

    private String extractComponentName(
            String reference,
            String componentType) {

        if (reference == null) {
            return null;
        }

        String expectedPrefix =
                COMPONENT_PREFIX + componentType + "/";

        if (!reference.startsWith(expectedPrefix)) {
            // External references and other reference types
            // are preserved but not dereferenced.
            return null;
        }

        String encodedName =
                reference.substring(expectedPrefix.length());

        if (encodedName.isBlank()) {
            return null;
        }

        return decodePointer(encodedName);
    }

    private String decodePointer(String value) {

        return value
                .replace("~1", "/")
                .replace("~0", "~");
    }

    // =========================================================
    // RESOURCE IDENTIFICATION
    // =========================================================

    private ResourceKey parseResourceKey(
            String tag,
            String path) {

        if (tag != null) {

            Matcher matcher = TAG_PATTERN.matcher(tag);

            if (matcher.matches()) {

                return new ResourceKey(
                        matcher.group(1),
                        matcher.group(2),
                        matcher.group(3),
                        tag
                );
            }

            return new ResourceKey(
                    "Unclassified",
                    "Unknown",
                    tag,
                    tag
            );
        }

        return new ResourceKey(
                "Unclassified",
                "Unknown",
                deriveResourceName(path),
                null
        );
    }

    private String deriveResourceName(String path) {

        if (path == null || path.isBlank()) {
            return "Unknown";
        }

        String[] segments = path.split("/");

        for (int i = segments.length - 1; i >= 0; i--) {

            String segment = segments[i];

            if (segment == null
                    || segment.isBlank()
                    || segment.startsWith("{")) {
                continue;
            }

            return segment;
        }

        return "Unknown";
    }

    // =========================================================
    // INTERNAL RESOURCE MODELS
    // =========================================================

    private record ResourceKey(
            String apiName,
            String apiVersion,
            String resourceName,
            String sourceTag
    ) {}

    private static class ResourceAccumulator {

        private final ResourceKey key;

        private final List<ResourceOperation> operations =
                new ArrayList<>();

        private ResourceAccumulator(ResourceKey key) {
            this.key = key;
        }

        private void addOperation(ResourceOperation operation) {
            operations.add(operation);
        }

        private LiferayResource toResource() {

            List<ResourceOperation> sortedOperations =
                    operations.stream()
                            .sorted(
                                    Comparator
                                            .comparing(ResourceOperation::path)
                                            .thenComparing(
                                                    ResourceOperation::httpMethod
                                            )
                            )
                            .toList();

            List<String> paths = sortedOperations.stream()
                    .map(ResourceOperation::path)
                    .collect(
                            java.util.stream.Collectors
                                    .toCollection(TreeSet::new)
                    )
                    .stream()
                    .toList();

            List<String> httpMethods = sortedOperations.stream()
                    .map(ResourceOperation::httpMethod)
                    .collect(
                            java.util.stream.Collectors
                                    .toCollection(TreeSet::new)
                    )
                    .stream()
                    .toList();

            return new LiferayResource(
                    key.apiName(),
                    key.apiVersion(),
                    key.resourceName(),
                    key.sourceTag(),
                    sortedOperations.size(),
                    paths,
                    httpMethods,
                    sortedOperations
            );
        }
    }
}
