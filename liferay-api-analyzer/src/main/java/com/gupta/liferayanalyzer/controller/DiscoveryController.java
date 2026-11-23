package com.gupta.liferayanalyzer.controller;

import com.gupta.liferayanalyzer.analyzer.LiferayResourceAnalyzer;
import com.gupta.liferayanalyzer.analyzer.RelationshipAnalyzer;
import com.gupta.liferayanalyzer.discovery.OpenApiDiscoveryService;
import com.gupta.liferayanalyzer.fetcher.LiferayConnectionService;
import com.gupta.liferayanalyzer.model.DiscoveredApiResponse;
import com.gupta.liferayanalyzer.model.LiferayResource;
import com.gupta.liferayanalyzer.model.ParsedOpenApiResponse;
import com.gupta.liferayanalyzer.parser.OpenApiParserService;
import com.gupta.liferayanalyzer.parser.OpenApiOperationExtractor;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation;
import java.util.List;

import com.gupta.liferayanalyzer.generator.DocumentationGenerator;
import com.gupta.liferayanalyzer.model.GeneratedDocumentationResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.gupta.liferayanalyzer.normalizer.DocumentationNormalizer;
import com.gupta.liferayanalyzer.normalizer.NormalizedDocumentation;

import com.gupta.liferayanalyzer.model.ResolvedSchemaDocumentation;
import com.gupta.liferayanalyzer.model.SchemaCatalogResponse;
import com.gupta.liferayanalyzer.model.SchemaRelationship;
import com.gupta.liferayanalyzer.parser.OpenApiSchemaResolver;

import io.swagger.v3.oas.models.OpenAPI;
import tools.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyzer/discover")
public class DiscoveryController {

    private final LiferayConnectionService connectionService;
    private final OpenApiDiscoveryService discoveryService;
    private final OpenApiParserService parserService;
    private final DocumentationNormalizer documentationNormalizer;
    private final OpenApiOperationExtractor operationExtractor;
    private final OpenApiSchemaResolver schemaResolver;
    private final RelationshipAnalyzer relationshipAnalyzer;
    private final LiferayResourceAnalyzer resourceAnalyzer;
    private final DocumentationGenerator documentationGenerator;

    public DiscoveryController(
            LiferayConnectionService connectionService,
            OpenApiDiscoveryService discoveryService,
            OpenApiParserService parserService,
            OpenApiOperationExtractor operationExtractor,
            OpenApiSchemaResolver schemaResolver,
            RelationshipAnalyzer relationshipAnalyzer,
            LiferayResourceAnalyzer resourceAnalyzer,
            DocumentationNormalizer documentationNormalizer,
            DocumentationGenerator documentationGenerator
    ) {
        this.connectionService = connectionService;
        this.discoveryService = discoveryService;
        this.parserService = parserService;
        this.operationExtractor = operationExtractor;
        this.schemaResolver = schemaResolver;
        this.relationshipAnalyzer = relationshipAnalyzer;
        this.resourceAnalyzer = resourceAnalyzer;
        this.documentationNormalizer = documentationNormalizer;
        this.documentationGenerator = documentationGenerator;
    }

    @GetMapping("/catalog")
    public tools.jackson.databind.JsonNode discoverCatalog() {
        return connectionService.fetchCatalog();
    }

    @GetMapping("/apis")
    public DiscoveredApiResponse discoverApis() {

        var catalog = connectionService.fetchCatalog();

        return discoveryService.discover(catalog);
    }

    @GetMapping("/parse")
    public ParsedOpenApiResponse parseOpenApi() {

        var catalog = connectionService.fetchCatalog();

        var parsedOpenApi = parserService.parse(catalog);

        return parserService.summarize(parsedOpenApi);
    }

    @GetMapping("/operations")
public List<ApiOperationDocumentation> extractOperations() {

    var catalog = connectionService.fetchCatalog();

    var parsedOpenApi = parserService.parse(catalog);

    return operationExtractor.extract(parsedOpenApi);
}

@GetMapping("/schemas/{schemaName}")
public ResolvedSchemaDocumentation resolveSchema(
        @PathVariable String schemaName
) {

    var catalog = connectionService.fetchCatalog();

    var parsedOpenApi = parserService.parse(catalog);

    return schemaResolver.resolve(
            parsedOpenApi,
            schemaName
    );
}


@GetMapping("/schemas")
public SchemaCatalogResponse getSchemas() {

    JsonNode catalog = connectionService.fetchCatalog();

    OpenAPI openAPI = parserService.parse(catalog);

    List<String> schemaNames = schemaResolver.getSchemaNames(openAPI);

    return new SchemaCatalogResponse(
            schemaNames.size(),
            schemaNames
    );
}


@GetMapping("/relationships")
public List<SchemaRelationship> getRelationships() {

    // Fetch the Liferay OpenAPI catalog
    JsonNode catalog = connectionService.fetchCatalog();

    // Parse the catalog into an OpenAPI object
    OpenAPI openAPI = parserService.parse(catalog);

    // Analyze schema relationships
    return relationshipAnalyzer.analyze(openAPI);
}


@GetMapping("/resources")
public List<LiferayResource> getResources() {

    // Fetch Liferay's OpenAPI catalog
    JsonNode catalog = connectionService.fetchCatalog();

    // Parse the specification
    OpenAPI openAPI = parserService.parse(catalog);

    // Discover and group Liferay resources
    return resourceAnalyzer.analyze(openAPI);
}

@GetMapping("/normalize")
public NormalizedDocumentation normalizeDocumentation() {
    return buildNormalizedDocumentation();
}

private NormalizedDocumentation buildNormalizedDocumentation() {

    JsonNode catalog = connectionService.fetchCatalog();

    OpenAPI openAPI = parserService.parse(catalog);

    // Do not expand schemas inline: the compact output stores
    // every schema once and references it by name.
    List<LiferayResource> resources =
            resourceAnalyzer.analyze(openAPI, false);

    List<SchemaRelationship> relationships =
            relationshipAnalyzer.analyze(openAPI);

    return documentationNormalizer.normalize(
            openAPI,
            resources,
            relationships
    );
}

@PostMapping("/generate")
public GeneratedDocumentationResponse generateDocumentation() {

    NormalizedDocumentation documentation =
            buildNormalizedDocumentation();

    Path generatedFile =
            documentationGenerator.generate(documentation);

    try {
        return new GeneratedDocumentationResponse(
                "Documentation generated successfully",
                generatedFile.getFileName().toString(),
                generatedFile.toString(),
                Files.size(generatedFile),
                documentation.resourceCount(),
                documentation.operationCount(),
                documentation.schemaCount(),
                documentation.relationshipCount()
        );

    } catch (java.io.IOException ex) {
        throw new IllegalStateException(
                "Could not read generated documentation file size",
                ex
        );
    }
}

}