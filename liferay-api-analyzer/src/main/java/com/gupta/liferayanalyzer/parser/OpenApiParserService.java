package com.gupta.liferayanalyzer.parser;

import com.gupta.liferayanalyzer.exception.LiferayApiException;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;

import com.gupta.liferayanalyzer.model.ParsedOpenApiResponse;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

@Service
public class OpenApiParserService {

    public OpenAPI parse(JsonNode jsonDocument) {

        if (jsonDocument == null || !jsonDocument.isObject()) {
            throw new LiferayApiException(
                    "Cannot parse OpenAPI document: invalid JSON object."
            );
        }

        String jsonContent = jsonDocument.toString();

        // Configure parsing without resolving external references.
        ParseOptions options = new ParseOptions();
        options.setResolve(false);

        // Parse the JSON content into Swagger's OpenAPI model.
        SwaggerParseResult result = new OpenAPIV3Parser()
                .readContents(jsonContent, null, options);

        if (result == null || result.getOpenAPI() == null) {

            String errorMessage = result == null
                    ? "Swagger Parser returned no result."
                    : String.join("; ", result.getMessages());

            throw new LiferayApiException(
                    "OpenAPI parsing failed: " + errorMessage
            );
        }

        return result.getOpenAPI();
    }

    public ParsedOpenApiResponse summarize(OpenAPI openAPI) {

    int pathCount = 0;
    int operationCount = 0;

    if (openAPI.getPaths() != null) {

        pathCount = openAPI.getPaths().size();

        for (Map.Entry<String, PathItem> entry
                : openAPI.getPaths().entrySet()) {

            PathItem pathItem = entry.getValue();

            if (pathItem != null) {
                operationCount += pathItem.readOperations().size();
            }
        }
    }

    int schemaCount = 0;

    if (openAPI.getComponents() != null
            && openAPI.getComponents().getSchemas() != null) {

        schemaCount = openAPI.getComponents()
                .getSchemas()
                .size();
    }

    int tagCount = openAPI.getTags() == null
            ? 0
            : openAPI.getTags().size();

    List<String> servers = openAPI.getServers() == null
            ? List.of()
            : openAPI.getServers()
                    .stream()
                    .map(server -> server.getUrl())
                    .toList();

    return new ParsedOpenApiResponse(

            openAPI.getInfo() == null
                    ? null
                    : openAPI.getInfo().getTitle(),

            openAPI.getInfo() == null
                    ? null
                    : openAPI.getInfo().getDescription(),

            openAPI.getOpenapi(),

            pathCount,

            operationCount,

            schemaCount,

            tagCount,

            servers
    );
}
}