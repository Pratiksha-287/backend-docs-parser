
package com.gupta.liferayanalyzer.analyzer;

import com.gupta.liferayanalyzer.fetcher.LiferayConnectionService;
import com.gupta.liferayanalyzer.model.AnalyzerStatistics;
import com.gupta.liferayanalyzer.model.LiferayResource;
import com.gupta.liferayanalyzer.parser.OpenApiParserService;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;

import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalyzerStatisticsService {

    private final LiferayConnectionService connectionService;
    private final OpenApiParserService parserService;
    private final LiferayResourceAnalyzer resourceAnalyzer;
    private final String specificationUrl;

    public AnalyzerStatisticsService(
            LiferayConnectionService connectionService,
            OpenApiParserService parserService,
            LiferayResourceAnalyzer resourceAnalyzer,
            @Value("${liferay.base-url}") String baseUrl,
            @Value("${liferay.discovery.catalog-path}") String catalogPath) {

        this.connectionService = connectionService;
        this.parserService = parserService;
        this.resourceAnalyzer = resourceAnalyzer;

        this.specificationUrl =
                baseUrl.replaceAll("/+$", "")
                + "/"
                + catalogPath.replaceAll("^/+", "");
    }

    public AnalyzerStatistics getStatistics() {

        // Step 1: Fetch the global OpenAPI specification.
        JsonNode catalog = connectionService.fetchCatalog();

        int specificationUrlsFetched = 1;

        // Step 2: Parse the fetched specification.
        OpenAPI openAPI = parserService.parse(catalog);

        int specificationDocumentsParsed = 1;

        // Step 3: Count unique API paths.
        int pathCount = openAPI.getPaths() == null
                ? 0
                : openAPI.getPaths().size();

        // Step 4: Count HTTP operations.
        int operationCount = openAPI.getPaths() == null
                ? 0
                : openAPI.getPaths()
                        .values()
                        .stream()
                        .filter(path -> path != null)
                        .mapToInt(path ->
                                path.readOperationsMap() == null
                                        ? 0
                                        : path.readOperationsMap().size())
                        .sum();

        // Step 5: Count component schemas.
        int schemaCount =
                openAPI.getComponents() == null
                        || openAPI.getComponents().getSchemas() == null
                        ? 0
                        : openAPI.getComponents()
                                .getSchemas()
                                .size();

        // Step 6: Analyze Liferay resources.
        List<LiferayResource> resources =
                resourceAnalyzer.analyze(openAPI);

        int resourceCount = resources.size();

        return new AnalyzerStatistics(
                specificationUrl,
                specificationUrlsFetched,
                specificationDocumentsParsed,
                pathCount,
                operationCount,
                schemaCount,
                resourceCount
        );
    }
}
