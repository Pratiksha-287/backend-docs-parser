package com.gupta.liferayanalyzer.parser;

import com.gupta.liferayanalyzer.model.CompactSchema;

import io.swagger.v3.oas.models.media.Schema;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Swagger {@link Schema} into a {@link CompactSchema} without
 * expanding component references.
 */
@Component
public class CompactSchemaConverter {

    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final int MAX_DEPTH = 40;

    public CompactSchema convert(Schema<?> schema) {
        return convert(schema, 0);
    }

    /** Short name for "#/components/schemas/Foo"; other refs unchanged. */
    public static String refName(String reference) {

        if (reference == null) {
            return null;
        }

        if (!reference.startsWith(SCHEMA_PREFIX)) {
            return reference;
        }

        return reference.substring(SCHEMA_PREFIX.length())
                .replace("~1", "/")
                .replace("~0", "~");
    }

    @SuppressWarnings("rawtypes")
    private CompactSchema convert(Schema<?> schema, int depth) {

        if (schema == null) {
            return null;
        }

        if (schema.get$ref() != null) {
            return new CompactSchema(
                    refName(schema.get$ref()),
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null
            );
        }

        if (depth > MAX_DEPTH) {
            return new CompactSchema(
                    null, schema.getType(), null,
                    "Schema nesting too deep; truncated.",
                    null, null, null, null, null, null, null, null,
                    null, null, null
            );
        }

        Map<String, CompactSchema> properties = null;

        if (schema.getProperties() != null
                && !schema.getProperties().isEmpty()) {

            properties = new LinkedHashMap<>();

            Map<String, Schema> source = schema.getProperties();

            for (Map.Entry<String, Schema> entry : source.entrySet()) {

                properties.put(
                        entry.getKey(),
                        convert((Schema<?>) entry.getValue(), depth + 1)
                );
            }
        }

        Object additionalProperties = null;

        Object rawAdditional = schema.getAdditionalProperties();

        if (rawAdditional instanceof Boolean allowed) {
            additionalProperties = allowed;
        } else if (rawAdditional instanceof Schema<?> additionalSchema) {
            additionalProperties = convert(additionalSchema, depth + 1);
        }

        return new CompactSchema(
                null,
                schema.getType(),
                schema.getFormat(),
                schema.getDescription(),
                enumValues(schema),
                schema.getRequired() == null
                        ? null
                        : List.copyOf(schema.getRequired()),
                properties,
                convert(schema.getItems(), depth + 1),
                convertAll(schema.getAllOf(), depth + 1),
                convertAll(schema.getOneOf(), depth + 1),
                convertAll(schema.getAnyOf(), depth + 1),
                additionalProperties,
                Boolean.TRUE.equals(schema.getReadOnly()) ? true : null,
                Boolean.TRUE.equals(schema.getWriteOnly()) ? true : null,
                Boolean.TRUE.equals(schema.getDeprecated()) ? true : null
        );
    }

    private List<String> enumValues(Schema<?> schema) {

        List<?> values = schema.getEnum();

        if (values == null || values.isEmpty()) {
            return null;
        }

        List<String> result = new ArrayList<>(values.size());

        for (Object value : values) {
            result.add(String.valueOf(value));
        }

        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<CompactSchema> convertAll(List<Schema> schemas, int depth) {

        if (schemas == null || schemas.isEmpty()) {
            return null;
        }

        List<CompactSchema> result = new ArrayList<>(schemas.size());

        for (Schema schema : schemas) {
            result.add(convert((Schema<?>) schema, depth));
        }

        return result;
    }
}
