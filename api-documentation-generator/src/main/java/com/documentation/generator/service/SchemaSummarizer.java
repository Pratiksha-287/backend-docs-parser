package com.documentation.generator.service;

import com.documentation.generator.model.DocumentationDocument.Relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns swagger-parser {@link Schema} objects into a compact JSON form.
 *
 * Type notation (also written to documentMetadata.conventions):
 *   string, integer(int64), string(date-time)   type(format)
 *   User                                        reference to schemas.User
 *   User[]                                      array of User
 *   map<string,User>                            dictionary of User
 *   oneOf<A|B>, anyOf<A|B>, allOf<A+B>          composition
 */
final class SchemaSummarizer {

    /** Inline object schemas are expanded at most this deep. */
    private static final int MAX_INLINE_DEPTH = 3;

    private static final int MAX_REF_DEPTH = 8;

    private final ObjectMapper mapper;

    @SuppressWarnings("rawtypes")
    private final Map<String, Schema> components;

    @SuppressWarnings("rawtypes")
    SchemaSummarizer(ObjectMapper mapper, OpenAPI openAPI) {

        this.mapper = mapper;

        this.components = openAPI.getComponents() != null
                && openAPI.getComponents().getSchemas() != null
                ? openAPI.getComponents().getSchemas()
                : Map.of();
    }

    boolean isComponent(String name) {
        return components.containsKey(name);
    }

    @SuppressWarnings("rawtypes")
    Map<String, Schema> components() {
        return components;
    }

    @SuppressWarnings("rawtypes")
    Schema componentSchema(String name) {
        return components.get(name);
    }

    static String refName(String ref) {

        int slash = ref.lastIndexOf('/');

        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    // ------------------------------------------------------------------
    // Type notation
    // ------------------------------------------------------------------

    String typeOf(Schema<?> schema) {

        if (schema == null) {
            return "any";
        }

        if (schema.get$ref() != null) {
            return refName(schema.get$ref());
        }

        String type = baseType(schema);

        if ("array".equals(type) || schema.getItems() != null) {
            return typeOf(schema.getItems()) + "[]";
        }

        if (notEmpty(schema.getOneOf())) {
            return "oneOf<" + joinTypes(schema.getOneOf(), "|") + ">";
        }

        if (notEmpty(schema.getAnyOf())) {
            return "anyOf<" + joinTypes(schema.getAnyOf(), "|") + ">";
        }

        if (notEmpty(schema.getAllOf())) {

            if (schema.getAllOf().size() == 1) {
                return typeOf(schema.getAllOf().get(0));
            }

            return hasProperties(schema)
                    ? "object"
                    : "allOf<" + joinTypes(schema.getAllOf(), "+") + ">";
        }

        Object additional = schema.getAdditionalProperties();

        if (additional instanceof Schema<?> valueSchema
                && !hasProperties(schema)) {
            return "map<string," + typeOf(valueSchema) + ">";
        }

        if (type == null) {
            return hasProperties(schema) ? "object" : "any";
        }

        String format = schema.getFormat();

        return format != null && !format.isBlank()
                ? type + "(" + format + ")"
                : type;
    }

    @SuppressWarnings("rawtypes")
    private String joinTypes(List<Schema> schemas, String separator) {

        List<String> names = new ArrayList<>();

        for (Schema<?> schema : schemas) {
            names.add(typeOf(schema));
        }

        return String.join(separator, names);
    }

    private static String baseType(Schema<?> schema) {

        if (schema.getType() != null) {
            return schema.getType();
        }

        // OpenAPI 3.1 schemas carry a set of types instead
        Set<String> types = schema.getTypes();

        if (types != null) {

            for (String type : types) {

                if (!"null".equals(type)) {
                    return type;
                }
            }
        }

        return null;
    }

    private static boolean isNullable(Schema<?> schema) {

        return Boolean.TRUE.equals(schema.getNullable())
                || (schema.getTypes() != null
                && schema.getTypes().contains("null"));
    }

    private static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean hasProperties(Schema<?> schema) {
        return schema.getProperties() != null
                && !schema.getProperties().isEmpty();
    }

    // ------------------------------------------------------------------
    // Inline descriptors (properties, parameters, request/response bodies)
    // ------------------------------------------------------------------

    /**
     * Compact description of a schema used in place. A $ref is described
     * by its name only; everything else carries just the facts that exist.
     */
    ObjectNode describe(Schema<?> schema, int depth) {

        ObjectNode node = mapper.createObjectNode();

        node.put("type", typeOf(schema));

        if (schema == null || schema.get$ref() != null) {
            return node;
        }

        putText(node, "description",
                TextInferenceSupport.clean(schema.getDescription()));

        if (isNullable(schema)) {
            node.put("nullable", true);
        }

        Schema<?> inner = schema.getItems() != null
                ? schema.getItems() : schema;

        // enum of the value itself, or of the array's items
        List<?> enumValues = schema.getEnum() != null
                ? schema.getEnum()
                : (inner != schema && inner.get$ref() == null
                ? inner.getEnum() : null);

        if (notEmpty(enumValues)) {

            ArrayNode values = node.putArray("enum");

            enumValues.forEach(value -> values.add(String.valueOf(value)));
        }

        putConstraints(node, schema);

        if (schema.getDefault() != null) {
            node.set("default", mapper.valueToTree(schema.getDefault()));
        }

        Object example = schema.getExample();

        if (example instanceof String || example instanceof Number
                || example instanceof Boolean) {
            node.set("example", mapper.valueToTree(example));
        }

        if (Boolean.TRUE.equals(schema.getReadOnly())) {
            node.put("readOnly", true);
        }

        if (Boolean.TRUE.equals(schema.getWriteOnly())) {
            node.put("writeOnly", true);
        }

        if (Boolean.TRUE.equals(schema.getDeprecated())) {
            node.put("deprecated", true);
        }

        if (inner.get$ref() == null
                && hasProperties(inner)
                && depth < MAX_INLINE_DEPTH) {

            node.set("properties", properties(inner, depth + 1));
        }

        return node;
    }

    private void putConstraints(ObjectNode node, Schema<?> schema) {

        if (schema.getMinimum() != null) {
            node.put("min", schema.getMinimum());
        }

        if (schema.getMaximum() != null) {
            node.put("max", schema.getMaximum());
        }

        if (schema.getMinLength() != null) {
            node.put("minLength", schema.getMinLength());
        }

        if (schema.getMaxLength() != null) {
            node.put("maxLength", schema.getMaxLength());
        }

        if (schema.getPattern() != null) {
            node.put("pattern", schema.getPattern());
        }

        if (schema.getMinItems() != null) {
            node.put("minItems", schema.getMinItems());
        }

        if (schema.getMaxItems() != null) {
            node.put("maxItems", schema.getMaxItems());
        }
    }

    @SuppressWarnings("rawtypes")
    private ObjectNode properties(Schema<?> holder, int depth) {

        ObjectNode result = mapper.createObjectNode();

        Set<String> required = holder.getRequired() != null
                ? new LinkedHashSet<>(holder.getRequired())
                : Set.of();

        Map<String, Schema> properties = holder.getProperties();

        properties.forEach((name, property) -> {

            ObjectNode propertyNode = describe(property, depth);

            if (required.contains(name)) {
                propertyNode.put("required", true);
            }

            result.set(name, propertyNode);
        });

        return result;
    }

    // ------------------------------------------------------------------
    // Reusable (component) schemas
    // ------------------------------------------------------------------

    /** Own properties plus those declared in inline allOf parts. */
    @SuppressWarnings("rawtypes")
    private Map<String, Schema> allProperties(
            Schema<?> schema, Set<String> requiredOut) {

        Map<String, Schema> merged = new LinkedHashMap<>();

        collectProperties(schema, merged, requiredOut);

        return merged;
    }

    @SuppressWarnings("rawtypes")
    private void collectProperties(
            Schema<?> schema,
            Map<String, Schema> merged,
            Set<String> requiredOut) {

        if (schema.getProperties() != null) {
            merged.putAll(schema.getProperties());
        }

        if (schema.getRequired() != null) {
            requiredOut.addAll(schema.getRequired());
        }

        if (schema.getAllOf() != null) {

            for (Schema<?> part : schema.getAllOf()) {

                if (part.get$ref() == null) {
                    collectProperties(part, merged, requiredOut);
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    ObjectNode describeComponent(Schema<?> schema) {

        ObjectNode node = mapper.createObjectNode();

        Set<String> required = new LinkedHashSet<>();

        Map<String, Schema> properties = allProperties(schema, required);

        boolean composed = notEmpty(schema.getAllOf())
                || notEmpty(schema.getOneOf())
                || notEmpty(schema.getAnyOf());

        node.put("type",
                (!properties.isEmpty() || composed)
                        ? "object" : typeOf(schema));

        putText(node, "description",
                TextInferenceSupport.clean(schema.getDescription()));

        if (Boolean.TRUE.equals(schema.getDeprecated())) {
            node.put("deprecated", true);
        }

        if (notEmpty(schema.getEnum())) {

            ArrayNode values = node.putArray("enum");

            schema.getEnum().forEach(
                    value -> values.add(String.valueOf(value)));
        }

        putNames(node, "extends", refsOf(schema.getAllOf()));
        putNames(node, "oneOf", typesOf(schema.getOneOf()));
        putNames(node, "anyOf", typesOf(schema.getAnyOf()));

        if (schema.getDiscriminator() != null
                && schema.getDiscriminator().getPropertyName() != null) {

            node.put("discriminator",
                    schema.getDiscriminator().getPropertyName());
        }

        if (!properties.isEmpty()) {

            ObjectNode props = mapper.createObjectNode();

            properties.forEach((name, property) -> {

                ObjectNode propertyNode = describe(property, 1);

                if (required.contains(name)) {
                    propertyNode.put("required", true);
                }

                props.set(name, propertyNode);
            });

            node.set("properties", props);
        }

        return node;
    }

    private void putNames(ObjectNode node, String key, List<String> names) {

        if (names.isEmpty()) {
            return;
        }

        ArrayNode array = node.putArray(key);

        names.forEach(array::add);
    }

    @SuppressWarnings("rawtypes")
    private List<String> refsOf(List<Schema> schemas) {

        List<String> names = new ArrayList<>();

        if (schemas != null) {

            for (Schema<?> schema : schemas) {

                if (schema.get$ref() != null) {
                    names.add(refName(schema.get$ref()));
                }
            }
        }

        return names;
    }

    @SuppressWarnings("rawtypes")
    private List<String> typesOf(List<Schema> schemas) {

        List<String> names = new ArrayList<>();

        if (schemas != null) {

            for (Schema<?> schema : schemas) {
                names.add(typeOf(schema));
            }
        }

        return names;
    }

    // ------------------------------------------------------------------
    // Relationships
    // ------------------------------------------------------------------

    /** Collects the names of all reusable schemas referenced by a schema. */
    @SuppressWarnings("rawtypes")
    void refTargets(Schema<?> schema, Set<String> out) {
        refTargets(schema, out, 0);
    }

    @SuppressWarnings("rawtypes")
    private void refTargets(Schema<?> schema, Set<String> out, int depth) {

        if (schema == null || depth > MAX_REF_DEPTH) {
            return;
        }

        if (schema.get$ref() != null) {

            String name = refName(schema.get$ref());

            if (isComponent(name)) {
                out.add(name);
            }

            return;
        }

        refTargets(schema.getItems(), out, depth + 1);

        if (schema.getAdditionalProperties() instanceof Schema<?> value) {
            refTargets(value, out, depth + 1);
        }

        for (List<Schema> group : List.of(
                nullSafe(schema.getAllOf()),
                nullSafe(schema.getOneOf()),
                nullSafe(schema.getAnyOf()))) {

            for (Schema<?> member : group) {
                refTargets(member, out, depth + 1);
            }
        }

        if (schema.getProperties() != null) {

            for (Object property : schema.getProperties().values()) {
                refTargets((Schema<?>) property, out, depth + 1);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static List<Schema> nullSafe(List<Schema> list) {
        return list == null ? List.of() : list;
    }

    /** Schema -> schema relationships of one reusable schema. */
    @SuppressWarnings("rawtypes")
    void collectRelationships(
            String name, Schema<?> schema, Set<Relationship> out) {

        for (String parent : refsOf(schema.getAllOf())) {

            if (isComponent(parent)) {

                out.add(new Relationship(name, parent, "EXTENDS", null, null,
                        name + " extends " + parent
                                + " and inherits its fields"));
            }
        }

        collectAlternatives(name, "ONE_OF", schema.getOneOf(), out);
        collectAlternatives(name, "ANY_OF", schema.getAnyOf(), out);

        Set<String> required = new LinkedHashSet<>();

        Map<String, Schema> properties = allProperties(schema, required);

        properties.forEach((field, property) -> {

            Set<String> targets = new LinkedHashSet<>();

            refTargets(property, targets);

            String container = containerOf(property);

            for (String target : targets) {

                boolean isRequired = required.contains(field);

                String type = "one".equals(container)
                        ? "HAS_ONE" : "HAS_MANY";

                String sentence = switch (container) {
                    case "list" -> name + "." + field
                            + " holds a list of " + target;
                    case "map" -> name + "." + field
                            + " holds a map of " + target + " values";
                    default -> name + "." + field + " holds one " + target
                            + (isRequired ? " (required)" : " (optional)");
                };

                out.add(new Relationship(name, target, type, field,
                        isRequired ? Boolean.TRUE : null, sentence));
            }
        });
    }

    @SuppressWarnings("rawtypes")
    private void collectAlternatives(
            String name, String type, List<Schema> alternatives,
            Set<Relationship> out) {

        if (!notEmpty(alternatives)) {
            return;
        }

        int index = 0;

        for (Schema<?> alternative : alternatives) {

            index++;

            if (alternative.get$ref() == null) {
                continue;
            }

            String target = refName(alternative.get$ref());

            if (!isComponent(target)) {
                continue;
            }

            out.add(new Relationship(name, target, type, null, null,
                    name + " may be a " + target + " (alternative "
                            + index + " of " + alternatives.size() + ")"));
        }
    }

    /** "one", "list" or "map" depending on how a property holds its value. */
    private String containerOf(Schema<?> property) {

        if (property == null) {
            return "one";
        }

        if ("array".equals(baseType(property)) || property.getItems() != null) {
            return "list";
        }

        if (property.getAdditionalProperties() instanceof Schema<?>
                && !hasProperties(property)) {
            return "map";
        }

        return "one";
    }

    // ------------------------------------------------------------------

    static void putText(ObjectNode node, String key, String value) {

        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }
}
