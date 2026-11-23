
package com.gupta.liferayanalyzer.model;

import java.util.List;

public record DiscoveredApi(

        String apiName,

        String version,

        String basePath,

        int pathCount,

        int operationCount,

        List<String> resources,

        List<String> httpMethods

) {
}
