
package com.gupta.liferayanalyzer.model;

import java.util.List;

public record LiferayResource(
        String apiName,
        String apiVersion,
        String resourceName,
        String sourceTag,
        int operationCount,
        List<String> paths,
        List<String> httpMethods,
        List<ResourceOperation> operations
) {
}
