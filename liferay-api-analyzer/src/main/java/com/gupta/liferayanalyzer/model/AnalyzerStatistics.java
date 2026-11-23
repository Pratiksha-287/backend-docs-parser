
package com.gupta.liferayanalyzer.model;

public record AnalyzerStatistics(
        String specificationUrl,
        int specificationUrlsFetched,
        int specificationDocumentsParsed,
        int pathCount,
        int operationCount,
        int schemaCount,
        int resourceCount
) {}
