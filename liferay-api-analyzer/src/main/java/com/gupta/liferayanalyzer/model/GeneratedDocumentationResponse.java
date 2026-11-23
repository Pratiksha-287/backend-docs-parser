
package com.gupta.liferayanalyzer.model;

public record GeneratedDocumentationResponse(
        String message,
        String fileName,
        String filePath,
        long fileSizeBytes,
        int resourceCount,
        int operationCount,
        int schemaCount,
        int relationshipCount
) {}
