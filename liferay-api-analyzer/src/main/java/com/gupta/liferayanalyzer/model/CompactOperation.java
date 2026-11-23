package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactOperation(
        String operationId,
        String method,
        String path,
        String summary,
        String description,

        /* Keys into the top-level "parameters" dictionary. */
        List<String> parameters,

        CompactRequestBody requestBody,

        /* Keyed by HTTP status code (or "default"). */
        Map<String, CompactResponse> responses,

        /* Only present when it differs from the global api.security. */
        List<Map<String, List<String>>> security
) {
}
