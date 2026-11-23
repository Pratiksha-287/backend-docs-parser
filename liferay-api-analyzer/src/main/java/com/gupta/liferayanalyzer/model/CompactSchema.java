package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A schema node that is NOT expanded: a reference to another component
 * schema is kept as its name ("ref") and is resolved through the top-level
 * "schemas" dictionary of the generated document. Every schema is therefore
 * written exactly once, no matter how many operations use it.
 *
 * Empty / null members are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactSchema(

        /* Name of the referenced component schema (or the raw $ref). */
        @JsonProperty("$ref") String ref,

        String type,
        String format,
        String description,

        @JsonProperty("enum") List<String> enumValues,

        List<String> required,

        Map<String, CompactSchema> properties,

        CompactSchema items,

        List<CompactSchema> allOf,
        List<CompactSchema> oneOf,
        List<CompactSchema> anyOf,

        /* Boolean, or a CompactSchema for map-like objects. */
        Object additionalProperties,

        Boolean readOnly,
        Boolean writeOnly,
        Boolean deprecated

) {
}
