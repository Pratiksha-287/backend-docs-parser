
package com.gupta.liferayanalyzer.analyzer;

import com.gupta.liferayanalyzer.model.RelationshipConfidence;
import com.gupta.liferayanalyzer.model.RelationshipEvidence;
import com.gupta.liferayanalyzer.model.RelationshipType;
import com.gupta.liferayanalyzer.model.SchemaRelationship;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RelationshipAnalyzer {

    /**
     * Analyzes all component schemas in the OpenAPI specification.
     */
    public List<SchemaRelationship> analyze(OpenAPI openAPI) {

        List<SchemaRelationship> relationships = new ArrayList<>();

        if (openAPI == null
                || openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null) {
            return relationships;
        }

        Map<String, Schema> schemas =
                openAPI.getComponents().getSchemas();

        schemas.forEach((schemaName, schema) -> {

            if (schema == null) {
                return;
            }

            // Tracks currently visited schema objects in each traversal.
            Set<Schema<?>> activeSchemas =
                    Collections.newSetFromMap(new IdentityHashMap<>());

            inspectSchema(
                    schemaName,
                    "",
                    schema,
                    RelationshipEvidence.PROPERTY_SCHEMA_REF,
                    false,
                    relationships,
                    activeSchemas
            );
        });

        return relationships;
    }

    /**
     * Recursively inspects a schema and its nested structures.
     */
    private void inspectSchema(
            String sourceSchema,
            String propertyPath,
            Schema<?> schema,
            RelationshipEvidence referenceEvidence,
            boolean propertyNode,
            List<SchemaRelationship> relationships,
            Set<Schema<?>> activeSchemas) {

        if (schema == null || !activeSchemas.add(schema)) {
            return;
        }

        try {

            // 1. Detect direct schema references.
            String reference = schema.get$ref();

            if (reference != null) {

                RelationshipType type =
                        isCompositionEvidence(referenceEvidence)
                                ? RelationshipType.COMPOSITION
                                : RelationshipType.SCHEMA_DEPENDENCY;

                addReferenceRelationship(
                        sourceSchema,
                        propertyPath,
                        reference,
                        type,
                        referenceEvidence,
                        relationships
                );
            }

            // 2. Detect inline nested objects.
            Map<String, Schema> properties = schema.getProperties();

            if (propertyNode
                    && "object".equalsIgnoreCase(schema.getType())
                    && properties != null
                    && !properties.isEmpty()) {

                relationships.add(new SchemaRelationship(
                        sourceSchema,
                        propertyPath,
                        null,
                        RelationshipType.SCHEMA_DEPENDENCY,
                        RelationshipEvidence.NESTED_OBJECT,
                        RelationshipConfidence.MEDIUM,
                        "Property contains an inline nested object schema."
                ));
            }

            // 3. Recursively inspect all properties.
            if (properties != null) {

                properties.forEach((propertyName, propertySchema) -> {

                    if (propertySchema == null) {
                        return;
                    }

                    String childPath = propertyPath.isBlank()
                            ? propertyName
                            : propertyPath + "." + propertyName;

                    // Detect possible identifier-based relationships.
                    if (isIdentifierField(propertyName)
                            && propertySchema.get$ref() == null
                            && propertySchema.getProperties() == null
                            && propertySchema.getItems() == null) {

                        relationships.add(new SchemaRelationship(
                                sourceSchema,
                                childPath,
                                null,
                                RelationshipType.POTENTIAL_RESOURCE_RELATIONSHIP,
                                RelationshipEvidence.IDENTIFIER_FIELD,
                                RelationshipConfidence.LOW,
                                "Property name suggests an identifier, "
                                        + "but no referenced target schema "
                                        + "was found."
                        ));
                    }

                    // Recursively inspect this property.
                    inspectSchema(
                            sourceSchema,
                            childPath,
                            propertySchema,
                            RelationshipEvidence.PROPERTY_SCHEMA_REF,
                            true,
                            relationships,
                            activeSchemas
                    );
                });
            }

            // 4. Recursively inspect array items.
            Schema<?> items = schema.getItems();

            if (items != null) {

                String itemsPath = propertyPath.isBlank()
                        ? "items"
                        : propertyPath + "[]";

                inspectSchema(
                        sourceSchema,
                        itemsPath,
                        items,
                        RelationshipEvidence.ARRAY_ITEM_SCHEMA_REF,
                        true,
                        relationships,
                        activeSchemas
                );
            }

            // 5. Inspect allOf composition.
            inspectComposition(
                    sourceSchema,
                    propertyPath,
                    schema.getAllOf(),
                    RelationshipEvidence.ALL_OF,
                    relationships,
                    activeSchemas
            );

            // 6. Inspect oneOf composition.
            inspectComposition(
                    sourceSchema,
                    propertyPath,
                    schema.getOneOf(),
                    RelationshipEvidence.ONE_OF,
                    relationships,
                    activeSchemas
            );

            // 7. Inspect anyOf composition.
            inspectComposition(
                    sourceSchema,
                    propertyPath,
                    schema.getAnyOf(),
                    RelationshipEvidence.ANY_OF,
                    relationships,
                    activeSchemas
            );

            // 8. Inspect schema-valued additionalProperties.
            Object additionalProperties =
                    schema.getAdditionalProperties();

            if (additionalProperties instanceof Schema<?> additionalSchema) {

                String additionalPath = propertyPath.isBlank()
                        ? "additionalProperties"
                        : propertyPath + ".additionalProperties";

                inspectSchema(
                        sourceSchema,
                        additionalPath,
                        additionalSchema,
                        RelationshipEvidence.PROPERTY_SCHEMA_REF,
                        true,
                        relationships,
                        activeSchemas
                );
            }

        } finally {

            // Remove after this branch so the same schema can be
            // inspected through another property path.
            activeSchemas.remove(schema);
        }
    }

    /**
     * Inspects schemas inside allOf, oneOf and anyOf.
     */
    private void inspectComposition(
            String sourceSchema,
            String propertyPath,
            List<Schema> composedSchemas,
            RelationshipEvidence evidence,
            List<SchemaRelationship> relationships,
            Set<Schema<?>> activeSchemas) {

        if (composedSchemas == null || composedSchemas.isEmpty()) {
            return;
        }

        for (int i = 0; i < composedSchemas.size(); i++) {

            Schema<?> composedSchema = composedSchemas.get(i);

            if (composedSchema == null) {
                continue;
            }

            String compositionPath = propertyPath.isBlank()
                    ? evidence.name().toLowerCase() + "[" + i + "]"
                    : propertyPath + "."
                            + evidence.name().toLowerCase()
                            + "[" + i + "]";

            inspectSchema(
                    sourceSchema,
                    compositionPath,
                    composedSchema,
                    evidence,
                    false,
                    relationships,
                    activeSchemas
            );
        }
    }

    /**
     * Creates a relationship from a schema reference.
     */
    private void addReferenceRelationship(
            String sourceSchema,
            String sourceProperty,
            String reference,
            RelationshipType relationshipType,
            RelationshipEvidence evidence,
            List<SchemaRelationship> relationships) {

        String targetSchema = extractSchemaName(reference);

        relationships.add(new SchemaRelationship(
                sourceSchema,
                sourceProperty,
                targetSchema,
                relationshipType,
                evidence,
                RelationshipConfidence.HIGH,
                "Schema reference detected: " + reference
        ));
    }

    /**
     * Extracts a component schema name from a local reference.
     */
    private String extractSchemaName(String reference) {

        String prefix = "#/components/schemas/";

        if (reference == null || !reference.startsWith(prefix)) {
            return reference;
        }

        String schemaName = reference.substring(prefix.length());

        return decodePointer(schemaName);
    }

    /**
     * Decodes JSON Pointer escaped characters.
     */
    private String decodePointer(String value) {

        return value
                .replace("~1", "/")
                .replace("~0", "~");
    }

    /**
     * Identifies composition evidence.
     */
    private boolean isCompositionEvidence(
            RelationshipEvidence evidence) {

        return evidence == RelationshipEvidence.ALL_OF
                || evidence == RelationshipEvidence.ONE_OF
                || evidence == RelationshipEvidence.ANY_OF;
    }

    /**
     * Checks whether a property name suggests an identifier.
     *
     * Supports:
     * id, organizationId, accountIds, organization_id
     *
     * Avoids treating ordinary words such as "valid" as IDs.
     */
    

private boolean isIdentifierField(String propertyName) {

    if (propertyName == null) {
        return false;
    }

    // Exclude identifiers belonging to the current entity.
    if (propertyName.equalsIgnoreCase("id")
            || propertyName.equalsIgnoreCase("ids")
            || propertyName.equalsIgnoreCase("uuid")) {
        return false;
    }

    return propertyName.matches(
            "(?i).*(Id|Ids|_id|_ids)$"
    );
}


}
