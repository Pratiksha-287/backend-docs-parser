
package com.gupta.liferayanalyzer.model;

import java.util.List;

public record DiscoveredApiResponse(

        String documentTitle,

        String openApiVersion,

        int totalApis,

        int totalPaths,

        int totalOperations,

        List<DiscoveredApi> apis

) {
}
