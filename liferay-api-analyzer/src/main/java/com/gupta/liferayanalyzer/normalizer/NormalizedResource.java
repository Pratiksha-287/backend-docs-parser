
package com.gupta.liferayanalyzer.normalizer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gupta.liferayanalyzer.model.CompactOperation;

import java.util.List;

/**
 * "paths" and "httpMethods" of the previous format were removed because
 * they are fully derivable from "operations".
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record NormalizedResource(
        String apiName,
        String apiVersion,
        String resourceName,

        /* Only present for operations without a parsable Liferay tag. */
        String sourceTag,

        int operationCount,
        List<CompactOperation> operations
) {}
