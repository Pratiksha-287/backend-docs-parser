package com.documentation.generator.service;

import com.documentation.generator.exception.ApiException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpecificationParserService {

    // YAML is a superset of JSON, so this mapper reads both formats.
    private final ObjectMapper yamlMapper;

    private final boolean resolveReferences;

    public SpecificationParserService(
            @Value("${documentation.parser.resolve-references:false}")
            boolean resolveReferences,
            @Value("${documentation.source.max-size-bytes:10485760}")
            int maxSize) {

        this.resolveReferences = resolveReferences;

        // The default SnakeYAML limit (3 MB) is lower than the maximum
        // specification size accepted by this application.
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(maxSize);

        this.yamlMapper = new ObjectMapper(
                YAMLFactory.builder()
                        .loaderOptions(loaderOptions)
                        .build()
        );
    }

    public ParsedSpecification parse(String content) {

        try {

            // Step 1: Detect the original specification version
            JsonNode root = yamlMapper.readTree(content);

            String sourceVersion = detectVersion(root);

            // Step 2: Parse the specification
            ParseOptions options = new ParseOptions();

            options.setResolve(resolveReferences);

            SwaggerParseResult result =
                    new OpenAPIParser().readContents(
                            content,
                            null,
                            options
                    );

            List<String> messages = new ArrayList<>();

            if (result != null && result.getMessages() != null) {
                messages.addAll(result.getMessages());
            }

            if (result == null || result.getOpenAPI() == null) {

                throw new ApiException(
                        "Unable to parse the specification"
                                + (messages.isEmpty()
                                ? ""
                                : ": " + String.join("; ", messages))
                );
            }

            return new ParsedSpecification(
                    sourceVersion,
                    result.getOpenAPI(),
                    messages
            );

        } catch (ApiException exception) {

            throw exception;

        } catch (Exception exception) {

            throw new ApiException(
                    "Specification parsing failed: "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    private String detectVersion(JsonNode root) {

        if (root == null || !root.isObject()) {
            throw new ApiException(
                    "Not a valid Swagger/OpenAPI document"
            );
        }

        if (root.has("swagger")
                && root.get("swagger").asText().startsWith("2.")) {

            return "2.0";
        }

        if (root.has("openapi")) {

            String version = root.get("openapi").asText();

            if (version.startsWith("3.0")) {
                return "3.0";
            }

            if (version.startsWith("3.1")) {
                return "3.1";
            }

            throw new ApiException(
                    "Unsupported OpenAPI version: " + version
            );
        }

        throw new ApiException("Not a valid Swagger/OpenAPI document");
    }

    public record ParsedSpecification(
            String sourceVersion,
            OpenAPI openAPI,
            List<String> messages
    ) {
    }
}
