package com.documentation.generator.dto;

import com.documentation.generator.model.CombinedDocumentationDocument;
import com.documentation.generator.model.DocumentationDocument;

import java.util.List;

public record GenerateResponse(
        String documentId,
        String downloadUrl,
        DocumentationDocument.Statistics statistics,
        List<CombinedDocumentationDocument.FailedApi> failedApis
) {
}
