
package com.gupta.liferayanalyzer.normalizer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record NormalizedApiMetadata(
        String title,
        String description,
        String version,
        String openApiVersion,
        List<String> servers,

        /* Global security requirements; operations inherit these unless
         * they declare their own "security". */
        List<Map<String, List<String>>> security
) {}
