
package com.gupta.liferayanalyzer.normalizer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gupta.liferayanalyzer.model.CompactParameter;
import com.gupta.liferayanalyzer.model.CompactRelationship;
import com.gupta.liferayanalyzer.model.CompactSchema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Compact documentation (format 2.0).
 *
 * Shared things are stored ONCE and referenced by name:
 *  - schemas    : every component schema, with "$ref" pointing at other keys
 *  - parameters : de-duplicated parameter definitions, referenced by key
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record NormalizedDocumentation(
        String documentVersion,
        Instant generatedAt,
        NormalizedApiMetadata api,
        int resourceCount,
        int operationCount,
        int schemaCount,
        int relationshipCount,
        List<NormalizedResource> resources,
        Map<String, CompactParameter> parameters,
        Map<String, CompactSchema> schemas,
        Map<String, List<CompactRelationship>> relationships
) {}
