
package com.gupta.liferayanalyzer.model;

import java.util.List;

public record SchemaCatalogResponse(
        int schemaCount,
        List<String> schemaNames
) {}
