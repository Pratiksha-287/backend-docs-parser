package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A relationship, listed under its source schema in the top-level
 * "relationships" dictionary (so the source schema is not repeated).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactRelationship(
        String property,
        String target,
        RelationshipType type,
        RelationshipEvidence evidence,
        RelationshipConfidence confidence
) {
}
