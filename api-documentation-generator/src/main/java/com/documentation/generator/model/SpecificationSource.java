package com.documentation.generator.model;

public record SpecificationSource(
        String content,
        String sourceLocation,
        String sourceType,
        String projectName
) {
}
