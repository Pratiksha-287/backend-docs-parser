package com.gupta.liferayanalyzer.parser;

import com.gupta.liferayanalyzer.model.ResolvedSchemaDocumentation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpenApiSchemaResolver {

    private static final String SCHEMA_REFERENCE_PREFIX =
            "#/components/schemas/";

    public ResolvedSchemaDocumentation resolve(
            OpenAPI openAPI,
            String schemaName
    ) {

        if (openAPI == null
                || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null) {

            throw new IllegalArgumentException(
                    "OpenAPI document does not contain component schemas."
            );
        }

        Schema<?> schema = openAPI.getComponents()
                .getSchemas()
                .get(schemaName);

        if (schema == null) {
            throw new IllegalArgumentException(
                    "Schema not found: " + schemaName
            );
        }

        Set<String> referenceStack = new LinkedHashSet<>();

        String reference = SCHEMA_REFERENCE_PREFIX + schemaName;

        referenceStack.add(reference);

        return resolveSchema(
                openAPI,
                schema,
                schemaName,
                reference,
                referenceStack
        );
    }

    private ResolvedSchemaDocumentation resolveSchema(
            OpenAPI openAPI,
            Schema<?> schema,
            String name,
            String reference,
            Set<String> referenceStack
    ) {

        if (schema == null) {
            return null;
        }

        /*
         * Resolve a schema that directly references another component.
         */
        if (schema.get$ref() != null) {

            return resolveReference(
                    openAPI,
                    schema.get$ref(),
                    referenceStack
            );
        }

        Map<String, ResolvedSchemaDocumentation> properties =
                resolveProperties(openAPI, schema, referenceStack);

        ResolvedSchemaDocumentation items =
                resolveSchema(
                        openAPI,
                        schema.getItems(),
                        null,
                        null,
                        referenceStack
                );

        List<ResolvedSchemaDocumentation> allOf =
                resolveComposition(
                        openAPI,
                        schema.getAllOf(),
                        referenceStack
                );

        List<ResolvedSchemaDocumentation> oneOf =
                resolveComposition(
                        openAPI,
                        schema.getOneOf(),
                        referenceStack
                );

        List<ResolvedSchemaDocumentation> anyOf =
                resolveComposition(
                        openAPI,
                        schema.getAnyOf(),
                        referenceStack
                );

        Boolean additionalPropertiesAllowed = null;

        ResolvedSchemaDocumentation additionalPropertiesSchema = null;

        Object additionalProperties = schema.getAdditionalProperties();

        if (additionalProperties instanceof Boolean allowed) {

            additionalPropertiesAllowed = allowed;

        } else if (additionalProperties instanceof Schema<?> additionalSchema) {

            additionalPropertiesSchema = resolveSchema(
                    openAPI,
                    additionalSchema,
                    null,
                    null,
                    referenceStack
            );
        }

        List<String> requiredProperties =
                schema.getRequired() == null
                        ? List.of()
                        : List.copyOf(schema.getRequired());

        return new ResolvedSchemaDocumentation(
                name,
                reference,
                schema.getType(),
                schema.getFormat(),
                schema.getDescription(),
                false,
                true,
                requiredProperties,
                properties,
                items,
                allOf,
                oneOf,
                anyOf,
                additionalPropertiesAllowed,
                additionalPropertiesSchema
        );
    }

    private ResolvedSchemaDocumentation resolveReference(
            OpenAPI openAPI,
            String reference,
            Set<String> referenceStack
    ) {

        /*
         * Only resolve local component schema references.
         * Preserve external and other reference types.
         */
        if (!reference.startsWith(SCHEMA_REFERENCE_PREFIX)) {

            return unresolvedReference(reference);
        }

        String schemaName = decodePointer(
                reference.substring(SCHEMA_REFERENCE_PREFIX.length())
        );

        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();

        Schema<?> referencedSchema = schemas.get(schemaName);

        if (referencedSchema == null) {
            return unresolvedReference(reference);
        }

        /*
         * Stop recursive expansion when a reference is already
         * present in the current recursion path.
         */
        if (referenceStack.contains(reference)) {

            return new ResolvedSchemaDocumentation(
                    schemaName,
                    reference,
                    null,
                    null,
                    null,
                    true,
                    true,
                    List.of(),
                    Map.of(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null
            );
        }

        Set<String> nextStack = new LinkedHashSet<>(referenceStack);

        nextStack.add(reference);

        return resolveSchema(
                openAPI,
                referencedSchema,
                schemaName,
                reference,
                nextStack
        );
    }

    private Map<String, ResolvedSchemaDocumentation> resolveProperties(
            OpenAPI openAPI,
            Schema<?> schema,
            Set<String> referenceStack
    ) {

        Map<String, ResolvedSchemaDocumentation> result =
                new LinkedHashMap<>();

        Map<String, Schema> properties = schema.getProperties();

        if (properties == null) {
            return result;
        }

        properties.forEach((propertyName, propertySchema) -> {

            result.put(
                    propertyName,
                    resolveSchema(
                            openAPI,
                            propertySchema,
                            propertyName,
                            null,
                            referenceStack
                    )
            );
        });

        return result;
    }

    private List<ResolvedSchemaDocumentation> resolveComposition(
            OpenAPI openAPI,
            List<Schema> schemas,
            Set<String> referenceStack
    ) {

        List<ResolvedSchemaDocumentation> result = new ArrayList<>();

        if (schemas == null) {
            return result;
        }

        for (Schema<?> schema : schemas) {

            result.add(
                    resolveSchema(
                            openAPI,
                            schema,
                            null,
                            null,
                            referenceStack
                    )
            );
        }

        return result;
    }

    private ResolvedSchemaDocumentation unresolvedReference(
            String reference
    ) {

        return new ResolvedSchemaDocumentation(
                null,
                reference,
                null,
                null,
                null,
                false,
                false,
                List.of(),
                Map.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    /*
     * JSON Pointer escaping:
     * ~1 represents /
     * ~0 represents ~
     */
    private String decodePointer(String value) {

        return value
                .replace("~1", "/")
                .replace("~0", "~");
    }


public List<String> getSchemaNames(OpenAPI openAPI) {

    if (openAPI.getComponents() == null
            || openAPI.getComponents().getSchemas() == null) {
        return List.of();
    }

    return openAPI.getComponents()
            .getSchemas()
            .keySet()
            .stream()
            .sorted()
            .toList();
}

}