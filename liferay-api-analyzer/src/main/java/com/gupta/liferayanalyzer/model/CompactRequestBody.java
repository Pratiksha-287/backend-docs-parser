package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactRequestBody(
        String description,

        /* Only present (true) when the body is required. */
        Boolean required,

        List<CompactContent> content
) {
}
