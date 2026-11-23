
package com.gupta.liferayanalyzer.model;

public record SchemaRelationship(

        // Schema containing the property
        String sourceSchema,

        // Property through which the relationship is identified
        String sourceProperty,

        // Referenced or potentially related schema
        String targetSchema,

        // Classification of the detected relationship
        RelationshipType relationshipType,

        // Evidence used to identify it
        RelationshipEvidence evidence,

        // Confidence in the classification
        RelationshipConfidence confidence,

        // Explanation of why it was identified
        String description

) {
}
