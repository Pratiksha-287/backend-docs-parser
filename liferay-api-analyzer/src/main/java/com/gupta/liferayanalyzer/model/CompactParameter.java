package com.gupta.liferayanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A parameter definition. Identical definitions (very common in Liferay:
 * page, pageSize, filter, search, sort, fields, ...) are stored once in the
 * top-level "parameters" dictionary and referenced by key from operations.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompactParameter(
        String name,
        String in,
        String description,

        /* Only present (true) for required parameters. */
        Boolean required,

        CompactSchema schema
) {
}
