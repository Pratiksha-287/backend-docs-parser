package com.gupta.liferayanalyzer.parser;

import com.gupta.liferayanalyzer.model.ApiOperationDocumentation;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation.ContentDocumentation;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation.ParameterDocumentation;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation.RequestBodyDocumentation;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation.ResponseDocumentation;
import com.gupta.liferayanalyzer.model.ApiOperationDocumentation.SecurityDocumentation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Schema;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenApiOperationExtractor {

    public List<ApiOperationDocumentation> extract(OpenAPI openAPI) {

        List<ApiOperationDocumentation> result = new ArrayList<>();

        if (openAPI == null || openAPI.getPaths() == null) {
            return result;
        }

        for (Map.Entry<String, PathItem> pathEntry
                : openAPI.getPaths().entrySet()) {

            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            if (pathItem == null) {
                continue;
            }

            Map<PathItem.HttpMethod, Operation> operations =
                    pathItem.readOperationsMap();

            for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry
                    : operations.entrySet()) {

                PathItem.HttpMethod httpMethod = operationEntry.getKey();
                Operation operation = operationEntry.getValue();

                if (operation == null) {
                    continue;
                }

                result.add(
                        extractOperation(
                                path,
                                httpMethod.name(),
                                pathItem,
                                operation
                        )
                );
            }
        }

        return result;
    }

    private ApiOperationDocumentation extractOperation(
            String path,
            String method,
            PathItem pathItem,
            Operation operation
    ) {

        return new ApiOperationDocumentation(
                path,
                method,
                operation.getOperationId(),
                operation.getSummary(),
                operation.getDescription(),
                operation.getTags() == null
                        ? List.of()
                        : operation.getTags(),
                extractParameters(pathItem, operation),
                extractRequestBody(operation),
                extractResponses(operation),
                extractSecurity(operation)
        );
    }

    /*
     * Path-level parameters apply to every operation under that path.
     * Operation-level parameters override matching path-level parameters.
     */
    private List<ParameterDocumentation> extractParameters(
            PathItem pathItem,
            Operation operation
    ) {

        Map<String, Parameter> parameters = new LinkedHashMap<>();

        if (pathItem.getParameters() != null) {
            for (Parameter parameter : pathItem.getParameters()) {
                addParameter(parameters, parameter);
            }
        }

        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                addParameter(parameters, parameter);
            }
        }

        return parameters.values()
                .stream()
                .map(this::toParameterDocumentation)
                .toList();
    }

    private void addParameter(
            Map<String, Parameter> parameters,
            Parameter parameter
    ) {

        if (parameter == null || parameter.getName() == null) {
            return;
        }

        String key = parameter.getName()
                + "::"
                + parameter.getIn();

        parameters.put(key, parameter);
    }

    private ParameterDocumentation toParameterDocumentation(
            Parameter parameter
    ) {

        Schema<?> schema = parameter.getSchema();

        return new ParameterDocumentation(
                parameter.getName(),
                parameter.getIn(),
                parameter.getDescription(),
                Boolean.TRUE.equals(parameter.getRequired()),
                schema == null ? null : schema.getType(),
                schema == null ? null : schema.getFormat(),
                schema == null ? null : schema.get$ref()
        );
    }

    private RequestBodyDocumentation extractRequestBody(
            Operation operation
    ) {

        var requestBody = operation.getRequestBody();

        if (requestBody == null) {
            return null;
        }

        List<ContentDocumentation> content =
                extractContent(requestBody.getContent());

        return new RequestBodyDocumentation(
                requestBody.getDescription(),
                Boolean.TRUE.equals(requestBody.getRequired()),
                content
        );
    }

    private List<ResponseDocumentation> extractResponses(
            Operation operation
    ) {

        List<ResponseDocumentation> result = new ArrayList<>();

        if (operation.getResponses() == null) {
            return result;
        }

        operation.getResponses().forEach((statusCode, response) -> {

            if (response == null) {
                return;
            }

            result.add(
                    new ResponseDocumentation(
                            statusCode,
                            response.getDescription(),
                            extractContent(response.getContent())
                    )
            );
        });

        return result;
    }

    private List<ContentDocumentation> extractContent(
            io.swagger.v3.oas.models.media.Content content
    ) {

        List<ContentDocumentation> result = new ArrayList<>();

        if (content == null) {
            return result;
        }

        content.forEach((mediaType, mediaTypeObject) -> {

            if (mediaTypeObject == null) {
                return;
            }

            Schema<?> schema = mediaTypeObject.getSchema();

            result.add(
                    new ContentDocumentation(
                            mediaType,
                            schema == null ? null : schema.getType(),
                            schema == null ? null : schema.getFormat(),
                            schema == null ? null : schema.get$ref()
                    )
            );
        });

        return result;
    }

    private List<SecurityDocumentation> extractSecurity(
            Operation operation
    ) {

        List<SecurityDocumentation> result = new ArrayList<>();

        if (operation.getSecurity() == null) {
            return result;
        }

        operation.getSecurity().forEach(requirement -> {

            requirement.forEach((scheme, scopes) -> {

                result.add(
                        new SecurityDocumentation(
                                scheme,
                                scopes == null ? List.of() : scopes
                        )
                );
            });
        });

        return result;
    }
}