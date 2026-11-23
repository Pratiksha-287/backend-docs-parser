package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Media types that share the same schema (e.g. application/json and
 * application/xml) are grouped into one entry instead of being repeated.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactContent(
        List<String> mediaTypes,
        CompactSchema schema
) {
}
