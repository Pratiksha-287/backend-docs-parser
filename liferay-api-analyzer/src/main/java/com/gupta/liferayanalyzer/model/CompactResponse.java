package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactResponse(
        String description,
        List<CompactContent> content
) {
}
