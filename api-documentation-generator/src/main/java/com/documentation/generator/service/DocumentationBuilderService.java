package com.documentation.generator.service;

import com.documentation.generator.model.DocumentationDocument;
import com.documentation.generator.model.DocumentationDocument.Relationship;
import com.documentation.generator.model.SpecificationSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the compact documentation JSON (contract 2.0).
 *
 * Design goals
 *  - one copy of everything: each endpoint appears once, the original
 *    specification is not embedded again
 *  - no noise: null / empty / default values are never written
 *  - complete: parameters, bodies, responses, constraints, security,
 *    schema fields, relations and workflows are all kept
 *  - explained: every relationship carries a sentence, and missing
 *    summaries / module / schema descriptions are inferred and flagged
 *    with descriptionInferred = true
 */
@Service
public class DocumentationBuilderService {

    static final String CONTRACT_VERSION = "2.0";

    private static final int MAX_SHOWN_IN_INFERRED_TEXT = 3;

    private final ObjectMapper mapper;

    private final WorkflowAnalyzerService workflowAnalyzer;

    private final boolean includeOriginal;

    public DocumentationBuilderService(
            ObjectMapper mapper,
            WorkflowAnalyzerService workflowAnalyzer,
            @Value("${documentation.output.include-original:false}")
            boolean includeOriginal) {

        this.mapper = mapper;
        this.workflowAnalyzer = workflowAnalyzer;
        this.includeOriginal = includeOriginal;
    }

    /** Legend explaining the compact notation; written once per file. */
    public JsonNode conventions() {

        ObjectNode node = mapper.createObjectNode();

        node.put("type", "integer(int64) = type(format); User = reference "
                + "to schemas.User; User[] = array; map<string,User> = "
                + "dictionary; oneOf<A|B>, anyOf<A|B>, allOf<A+B> = "
                + "composition");

        node.put("flags", "required / nullable / readOnly / writeOnly / "
                + "deprecated / public are written only when true");

        node.put("constraints", "min, max, minLength, maxLength, pattern, "
                + "minItems, maxItems, enum, default");

        node.put("body", "mediaType is omitted for application/json; "
                + "'alternatives' lists bodies that differ per media type");

        node.put("auth", "alternative security requirements; 'a + b' means "
                + "both are needed; name[scopes]; public=true means "
                + "explicitly no authentication");

        node.put("relationships", "from/to use 'METHOD /path' for endpoints "
                + "and schema names for schemas; via = field, status code "
                + "or parameter name");

        node.put("descriptionInferred", "true when the text was generated "
                + "from names and usage because the specification has none");

        return node;
    }

    public DocumentationDocument build(
            SpecificationSource source,
            SpecificationParserService.ParsedSpecification parsed) {

        return build(source, parsed, true);
    }

    public DocumentationDocument build(
            SpecificationSource source,
            SpecificationParserService.ParsedSpecification parsed,
            boolean withConventions) {

        OpenAPI openAPI = parsed.openAPI();

        SchemaSummarizer summarizer = new SchemaSummarizer(mapper, openAPI);

        Set<Relationship> relationships = new LinkedHashSet<>();

        // module (tag) -> endpoints, in first-seen order
        Map<String, List<JsonNode>> endpointsByModule = new LinkedHashMap<>();
        Map<String, Set<String>> schemasByModule = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> methodsByModule =
                new LinkedHashMap<>();

        int endpointCount = 0;

        if (openAPI.getPaths() != null) {

            for (Map.Entry<String, PathItem> pathEntry :
                    openAPI.getPaths().entrySet()) {

                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();

                if (pathItem == null) {
                    continue;
                }

                for (Map.Entry<PathItem.HttpMethod, Operation> entry :
                        pathItem.readOperationsMap().entrySet()) {

                    String method = entry.getKey().name();
                    Operation operation = entry.getValue();

                    List<Relationship> endpointRelations = new ArrayList<>();

                    ObjectNode endpoint = buildEndpoint(
                            path, method, pathItem, operation, openAPI,
                            summarizer, endpointRelations);

                    // An endpoint is stored once, under its primary tag.
                    String module = operation.getTags() != null
                            && !operation.getTags().isEmpty()
                            ? operation.getTags().get(0) : "General";

                    endpointsByModule
                            .computeIfAbsent(module, k -> new ArrayList<>())
                            .add(endpoint);

                    methodsByModule
                            .computeIfAbsent(module, k -> new LinkedHashMap<>())
                            .merge(method, 1, Integer::sum);

                    Set<String> moduleSchemas = schemasByModule
                            .computeIfAbsent(module, k -> new LinkedHashSet<>());

                    for (Relationship relation : endpointRelations) {
                        moduleSchemas.add(relation.to());
                    }

                    relationships.addAll(endpointRelations);

                    endpointCount++;
                }
            }
        }

        // ---- reusable schemas -----------------------------------------

        ObjectNode schemas = mapper.createObjectNode();

        summarizer.components().forEach((name, schema) -> {

            schemas.set(name, summarizer.describeComponent(schema));

            summarizer.collectRelationships(name, schema, relationships);
        });

        inferSchemaDescriptions(schemas, relationships);

        // ---- modules ---------------------------------------------------

        List<DocumentationDocument.Module> modules = new ArrayList<>();

        endpointsByModule.forEach((name, endpoints) -> {

            String description = openAPI.getTags() == null ? null
                    : openAPI.getTags().stream()
                    .filter(tag -> name.equals(tag.getName()))
                    .map(Tag::getDescription)
                    .filter(Objects::nonNull)
                    .map(TextInferenceSupport::clean)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            boolean inferred = false;

            List<String> moduleSchemas =
                    new ArrayList<>(schemasByModule.get(name));

            if (description == null) {

                description = inferModuleDescription(
                        name, methodsByModule.get(name), moduleSchemas);

                inferred = true;
            }

            modules.add(new DocumentationDocument.Module(
                    name,
                    description,
                    inferred ? Boolean.TRUE : null,
                    moduleSchemas,
                    endpoints));
        });

        // ---- workflows, metadata, statistics ---------------------------

        List<DocumentationDocument.WorkflowCandidate> workflows =
                workflowAnalyzer.analyze(openAPI);

        List<String> servers = new ArrayList<>();

        if (openAPI.getServers() != null) {

            for (Server server : openAPI.getServers()) {

                if (server.getUrl() == null) {
                    continue;
                }

                String note = TextInferenceSupport.clean(
                        server.getDescription());

                servers.add(note == null
                        ? server.getUrl()
                        : server.getUrl() + " (" + note + ")");
            }
        }

        DocumentationDocument.Metadata metadata =
                new DocumentationDocument.Metadata(
                        source.projectName(),
                        source.sourceLocation(),
                        source.sourceType(),
                        parsed.sourceVersion(),
                        openAPI.getOpenapi(),
                        openAPI.getInfo() != null
                                ? openAPI.getInfo().getTitle() : null,
                        openAPI.getInfo() != null
                                ? TextInferenceSupport.clean(
                                openAPI.getInfo().getDescription())
                                : null,
                        openAPI.getInfo() != null
                                ? openAPI.getInfo().getVersion() : null,
                        servers,
                        withConventions ? conventions() : null,
                        Instant.now()
                );

        DocumentationDocument.Statistics statistics =
                new DocumentationDocument.Statistics(
                        endpointCount,
                        schemas.size(),
                        modules.size(),
                        relationships.size()
                );

        return new DocumentationDocument(
                CONTRACT_VERSION,
                metadata,
                statistics,
                modules,
                schemas,
                securitySchemes(openAPI),
                new ArrayList<>(relationships),
                workflows,
                parsed.messages(),
                includeOriginal ? mapper.valueToTree(openAPI) : null
        );
    }

    // ------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------

    private ObjectNode buildEndpoint(
            String path,
            String method,
            PathItem pathItem,
            Operation operation,
            OpenAPI openAPI,
            SchemaSummarizer summarizer,
            List<Relationship> relations) {

        String id = method + " " + path;

        ObjectNode endpoint = mapper.createObjectNode();

        endpoint.put("operationId", operation.getOperationId() != null
                ? operation.getOperationId()
                : TextInferenceSupport.syntheticOperationId(method, path));

        endpoint.put("method", method);
        endpoint.put("path", path);

        String summary = TextInferenceSupport.clean(operation.getSummary());
        String description =
                TextInferenceSupport.clean(operation.getDescription());

        boolean inferred = false;

        if (summary == null && description == null) {

            summary = TextInferenceSupport.inferSummary(
                    method, path, operation.getOperationId());

            inferred = true;
        }

        if (summary != null && summary.equals(description)) {
            description = null;
        }

        SchemaSummarizer.putText(endpoint, "summary", summary);
        SchemaSummarizer.putText(endpoint, "description", description);

        if (inferred) {
            endpoint.put("descriptionInferred", true);
        }

        if (operation.getTags() != null && operation.getTags().size() > 1) {
            endpoint.set("tags", mapper.valueToTree(operation.getTags()));
        }

        if (Boolean.TRUE.equals(operation.getDeprecated())) {
            endpoint.put("deprecated", true);
        }

        putSecurity(endpoint, operation, openAPI);

        putParameters(endpoint, id, pathItem, operation, openAPI,
                summarizer, relations);

        putRequestBody(endpoint, id, operation, openAPI, summarizer,
                relations);

        putResponses(endpoint, id, operation, openAPI, summarizer, relations);

        if (operation.getExternalDocs() != null
                && operation.getExternalDocs().getUrl() != null) {

            endpoint.put("externalDocs",
                    operation.getExternalDocs().getUrl());
        }

        if (operation.getCallbacks() != null
                && !operation.getCallbacks().isEmpty()) {

            endpoint.set("callbacks",
                    mapper.valueToTree(operation.getCallbacks().keySet()));
        }

        return endpoint;
    }

    private void putSecurity(
            ObjectNode endpoint, Operation operation, OpenAPI openAPI) {

        List<SecurityRequirement> requirements =
                operation.getSecurity() != null
                        ? operation.getSecurity()
                        : openAPI.getSecurity();

        if (requirements == null) {
            return;
        }

        if (requirements.isEmpty()) {

            // An explicit empty list on the operation turns off the
            // globally declared security.
            if (operation.getSecurity() != null
                    && openAPI.getSecurity() != null
                    && !openAPI.getSecurity().isEmpty()) {

                endpoint.put("public", true);
            }

            return;
        }

        ArrayNode auth = endpoint.putArray("auth");

        for (SecurityRequirement requirement : requirements) {

            List<String> parts = new ArrayList<>();

            requirement.forEach((scheme, scopes) -> parts.add(
                    scopes == null || scopes.isEmpty()
                            ? scheme
                            : scheme + "[" + String.join(",", scopes) + "]"));

            if (!parts.isEmpty()) {
                auth.add(String.join(" + ", parts));
            }
        }

        if (auth.isEmpty()) {
            endpoint.remove("auth");
        }
    }

    // ---- parameters ----------------------------------------------------

    private void putParameters(
            ObjectNode endpoint,
            String id,
            PathItem pathItem,
            Operation operation,
            OpenAPI openAPI,
            SchemaSummarizer summarizer,
            List<Relationship> relations) {

        // operation level overrides path level for the same name + location
        Map<String, Parameter> merged = new LinkedHashMap<>();

        for (List<Parameter> level : java.util.Arrays.asList(
                pathItem.getParameters(), operation.getParameters())) {

            if (level == null) {
                continue;
            }

            for (Parameter declared : level) {

                Parameter parameter = resolve(declared, openAPI);

                merged.put(parameter.getIn() + ":" + parameter.getName(),
                        parameter);
            }
        }

        if (merged.isEmpty()) {
            return;
        }

        ArrayNode array = endpoint.putArray("parameters");

        for (Parameter parameter : merged.values()) {

            ObjectNode node = mapper.createObjectNode();

            node.put("name", parameter.getName());
            node.put("in", parameter.getIn());

            ObjectNode typeInfo = summarizer.describe(
                    parameter.getSchema(), 2);

            // parameter text wins over the schema's own text
            String parameterText =
                    TextInferenceSupport.clean(parameter.getDescription());

            if (parameterText != null) {
                typeInfo.put("description", parameterText);
            }

            node.setAll(typeInfo);

            boolean required = Boolean.TRUE.equals(parameter.getRequired())
                    || "path".equals(parameter.getIn());

            if (required) {
                node.put("required", true);
            }

            if (Boolean.TRUE.equals(parameter.getDeprecated())) {
                node.put("deprecated", true);
            }

            // Spring MVC models (Pageable, search filters, ...) are often
            // exposed as one query parameter that stands for many fields.
            putExpandedFields(node, parameter, summarizer);

            array.add(node);

            Set<String> targets = new LinkedHashSet<>();

            summarizer.refTargets(parameter.getSchema(), targets);

            for (String target : targets) {

                relations.add(new Relationship(
                        id, target, "PARAMETER", parameter.getName(),
                        required ? Boolean.TRUE : null,
                        id + " takes " + parameter.getIn() + " parameter '"
                                + parameter.getName() + "' of type "
                                + target));
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void putExpandedFields(
            ObjectNode node, Parameter parameter,
            SchemaSummarizer summarizer) {

        if (!"query".equals(parameter.getIn())
                || parameter.getSchema() == null
                || parameter.getSchema().get$ref() == null) {
            return;
        }

        Schema target = summarizer.componentSchema(
                SchemaSummarizer.refName(parameter.getSchema().get$ref()));

        if (target == null || target.getProperties() == null
                || target.getProperties().isEmpty()) {
            return;
        }

        ArrayNode fields = node.putArray("fields");

        ((Map<String, Schema>) target.getProperties())
                .keySet().forEach(fields::add);
    }

    private Parameter resolve(Parameter parameter, OpenAPI openAPI) {

        if (parameter.get$ref() != null
                && openAPI.getComponents() != null
                && openAPI.getComponents().getParameters() != null) {

            Parameter resolved = openAPI.getComponents().getParameters()
                    .get(SchemaSummarizer.refName(parameter.get$ref()));

            if (resolved != null) {
                return resolved;
            }
        }

        return parameter;
    }

    // ---- request body ----------------------------------------------------

    private void putRequestBody(
            ObjectNode endpoint,
            String id,
            Operation operation,
            OpenAPI openAPI,
            SchemaSummarizer summarizer,
            List<Relationship> relations) {

        RequestBody requestBody = operation.getRequestBody();

        if (requestBody == null) {
            return;
        }

        if (requestBody.get$ref() != null
                && openAPI.getComponents() != null
                && openAPI.getComponents().getRequestBodies() != null) {

            RequestBody resolved = openAPI.getComponents().getRequestBodies()
                    .get(SchemaSummarizer.refName(requestBody.get$ref()));

            if (resolved != null) {
                requestBody = resolved;
            }
        }

        Body body = body(requestBody.getContent(), summarizer);

        ObjectNode node = mapper.createObjectNode();

        if (Boolean.TRUE.equals(requestBody.getRequired())) {
            node.put("required", true);
        }

        SchemaSummarizer.putText(node, "description",
                TextInferenceSupport.clean(requestBody.getDescription()));

        if (body != null) {
            node.setAll(body.node());
        }

        if (node.isEmpty()) {
            return;
        }

        endpoint.set("requestBody", node);

        if (body != null) {

            for (String target : body.targets()) {

                relations.add(new Relationship(
                        id, target, "REQUEST_BODY", "body",
                        Boolean.TRUE.equals(requestBody.getRequired())
                                ? Boolean.TRUE : null,
                        id + " accepts " + target
                                + " as its request body"));
            }
        }
    }

    // ---- responses ---------------------------------------------------------

    private void putResponses(
            ObjectNode endpoint,
            String id,
            Operation operation,
            OpenAPI openAPI,
            SchemaSummarizer summarizer,
            List<Relationship> relations) {

        if (operation.getResponses() == null
                || operation.getResponses().isEmpty()) {
            return;
        }

        ObjectNode responses = endpoint.putObject("responses");

        for (Map.Entry<String, ApiResponse> entry :
                operation.getResponses().entrySet()) {

            String status = entry.getKey();

            ApiResponse response = entry.getValue();

            if (response == null) {
                continue;
            }

            if (response.get$ref() != null
                    && openAPI.getComponents() != null
                    && openAPI.getComponents().getResponses() != null) {

                ApiResponse resolved = openAPI.getComponents().getResponses()
                        .get(SchemaSummarizer.refName(response.get$ref()));

                if (resolved != null) {
                    response = resolved;
                }
            }

            Body body = body(response.getContent(), summarizer);

            ObjectNode node = mapper.createObjectNode();

            String description =
                    TextInferenceSupport.clean(response.getDescription());

            // "OK", "Not Found" ... next to a typed body add nothing
            if (description != null
                    && !(body != null && isGenericReason(status, description))) {

                node.put("description", description);
            }

            if (body != null) {
                node.setAll(body.node());
            }

            responses.set(status, node);

            if (body != null) {

                for (String target : body.targets()) {
                    relations.add(new Relationship(
                            id, target, "RESPONSE", status, null,
                            responseSentence(id, target, status)));
                }
            }
        }
    }

    private static boolean isGenericReason(String status, String description) {

        try {

            HttpStatus resolved = HttpStatus.resolve(Integer.parseInt(status));

            return resolved != null
                    && resolved.getReasonPhrase()
                    .equalsIgnoreCase(description);

        } catch (NumberFormatException exception) {

            return false;
        }
    }

    private static String responseSentence(
            String id, String target, String status) {

        if ("default".equalsIgnoreCase(status)) {
            return id + " returns " + target
                    + " for any status not listed explicitly";
        }

        boolean error = status.startsWith("4") || status.startsWith("5");

        return error
                ? id + " returns " + target + " on error " + status
                : id + " returns " + target + " (" + status + ")";
    }

    // ---- body (request and response content) -------------------------------

    private record Body(ObjectNode node, Set<String> targets) {
    }

    /**
     * Collapses the per-media-type map. Spring services frequently
     * publish the same schema under several media types; media types that
     * share the same schema become one entry.
     */
    private Body body(Content content, SchemaSummarizer summarizer) {

        if (content == null || content.isEmpty()) {
            return null;
        }

        Map<String, List<String>> mediaTypesBySignature =
                new LinkedHashMap<>();

        Map<String, ObjectNode> descriptorBySignature =
                new LinkedHashMap<>();

        Set<String> targets = new LinkedHashSet<>();

        for (Map.Entry<String, MediaType> entry : content.entrySet()) {

            Schema<?> schema = entry.getValue() == null
                    ? null : entry.getValue().getSchema();

            ObjectNode descriptor = summarizer.describe(schema, 0);

            summarizer.refTargets(schema, targets);

            String signature = descriptor.toString();

            mediaTypesBySignature
                    .computeIfAbsent(signature, k -> new ArrayList<>())
                    .add(entry.getKey());

            descriptorBySignature.putIfAbsent(signature, descriptor);
        }

        ObjectNode result = mapper.createObjectNode();

        if (mediaTypesBySignature.size() == 1) {

            String signature = mediaTypesBySignature.keySet().iterator().next();

            putMediaType(result, mediaTypesBySignature.get(signature));

            result.setAll(descriptorBySignature.get(signature));

        } else {

            ArrayNode alternatives = result.putArray("alternatives");

            mediaTypesBySignature.forEach((signature, mediaTypes) -> {

                ObjectNode alternative = mapper.createObjectNode();

                putMediaType(alternative, mediaTypes);

                alternative.setAll(descriptorBySignature.get(signature));

                alternatives.add(alternative);
            });
        }

        return new Body(result, targets);
    }

    private static final Set<String> DEFAULT_MEDIA_TYPES =
            Set.of("application/json", "*/*");

    private void putMediaType(ObjectNode node, List<String> mediaTypes) {

        if (DEFAULT_MEDIA_TYPES.containsAll(mediaTypes)) {
            return;
        }

        if (mediaTypes.size() == 1) {
            node.put("mediaType", mediaTypes.get(0));
        } else {
            node.set("mediaType", mapper.valueToTree(mediaTypes));
        }
    }

    // ------------------------------------------------------------------
    // Security schemes
    // ------------------------------------------------------------------

    private JsonNode securitySchemes(OpenAPI openAPI) {

        ObjectNode result = mapper.createObjectNode();

        if (openAPI.getComponents() == null
                || openAPI.getComponents().getSecuritySchemes() == null) {
            return result;
        }

        openAPI.getComponents().getSecuritySchemes().forEach((name, scheme) -> {

            ObjectNode node = mapper.createObjectNode();

            if (scheme.getType() != null) {
                node.put("type", scheme.getType().toString());
            }

            SchemaSummarizer.putText(node, "scheme", scheme.getScheme());
            SchemaSummarizer.putText(node, "bearerFormat",
                    scheme.getBearerFormat());

            if (scheme.getIn() != null) {
                node.put("in", scheme.getIn().toString());
            }

            SchemaSummarizer.putText(node, "name", scheme.getName());
            SchemaSummarizer.putText(node, "openIdConnectUrl",
                    scheme.getOpenIdConnectUrl());
            SchemaSummarizer.putText(node, "description",
                    TextInferenceSupport.clean(scheme.getDescription()));

            putFlows(node, scheme.getFlows());

            result.set(name, node);
        });

        return result;
    }

    private void putFlows(ObjectNode node, OAuthFlows flows) {

        if (flows == null) {
            return;
        }

        ObjectNode result = mapper.createObjectNode();

        putFlow(result, "implicit", flows.getImplicit());
        putFlow(result, "password", flows.getPassword());
        putFlow(result, "clientCredentials", flows.getClientCredentials());
        putFlow(result, "authorizationCode", flows.getAuthorizationCode());

        if (!result.isEmpty()) {
            node.set("flows", result);
        }
    }

    private void putFlow(ObjectNode parent, String name, OAuthFlow flow) {

        if (flow == null) {
            return;
        }

        ObjectNode node = mapper.createObjectNode();

        SchemaSummarizer.putText(node, "authorizationUrl",
                flow.getAuthorizationUrl());
        SchemaSummarizer.putText(node, "tokenUrl", flow.getTokenUrl());

        if (flow.getScopes() != null && !flow.getScopes().isEmpty()) {
            node.set("scopes", mapper.valueToTree(flow.getScopes()));
        }

        parent.set(name, node);
    }

    // ------------------------------------------------------------------
    // Inferred descriptions
    // ------------------------------------------------------------------

    private String inferModuleDescription(
            String name,
            Map<String, Integer> methods,
            List<String> schemas) {

        List<String> counts = new ArrayList<>();

        methods.forEach((method, count) ->
                counts.add(count > 1 ? count + " " + method : method));

        StringBuilder text = new StringBuilder(
                TextInferenceSupport.humanizeTag(name))
                .append(" operations (")
                .append(String.join(", ", counts))
                .append(")");

        if (!schemas.isEmpty()) {

            text.append("; works with ")
                    .append(TextInferenceSupport.listWithLimit(
                            schemas, MAX_SHOWN_IN_INFERRED_TEXT + 2));
        }

        return text.append('.').toString();
    }

    /**
     * Schemas without a description get a short usage sentence built from
     * the relationships already collected, e.g.
     * "Request body of POST /users; returned by GET /users/{id}".
     */
    private void inferSchemaDescriptions(
            ObjectNode schemas, Set<Relationship> relationships) {

        Map<String, List<Relationship>> incoming = new LinkedHashMap<>();

        for (Relationship relation : relationships) {

            incoming.computeIfAbsent(relation.to(), k -> new ArrayList<>())
                    .add(relation);
        }

        schemas.fieldNames().forEachRemaining(name -> {

            JsonNode schema = schemas.get(name);

            if (schema.has("description")) {
                return;
            }

            String usage = describeUsage(
                    incoming.getOrDefault(name, List.of()));

            if (usage != null) {

                ((ObjectNode) schema).put("description", usage);
                ((ObjectNode) schema).put("descriptionInferred", true);
            }
        });
    }

    private String describeUsage(List<Relationship> incoming) {

        List<String> requestBodies = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> parents = new ArrayList<>();

        for (Relationship relation : incoming) {

            switch (relation.type()) {

                case "REQUEST_BODY" -> addDistinct(requestBodies,
                        relation.from());

                case "RESPONSE" -> {
                    String status = relation.via();
                    boolean error = status != null
                            && (status.startsWith("4")
                            || status.startsWith("5"));
                    addDistinct(error ? errors : responses, relation.from());
                }

                case "PARAMETER" -> addDistinct(parameters, relation.from());

                case "HAS_ONE", "HAS_MANY" -> addDistinct(fields,
                        relation.from() + "." + relation.via());

                case "EXTENDS", "ONE_OF", "ANY_OF" ->
                        addDistinct(parents, relation.from());

                default -> {
                }
            }
        }

        List<String> parts = new ArrayList<>();

        addPart(parts, "Request body of ", requestBodies);
        addPart(parts, "returned by ", responses);
        addPart(parts, "error payload of ", errors);
        addPart(parts, "query/parameter model of ", parameters);
        addPart(parts, "used as field ", fields);
        addPart(parts, "related to ", parents);

        if (parts.isEmpty()) {
            return null;
        }

        return TextInferenceSupport.capitalize(String.join("; ", parts));
    }

    private static void addDistinct(List<String> list, String value) {

        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static void addPart(
            List<String> parts, String prefix, List<String> items) {

        if (!items.isEmpty()) {

            parts.add(prefix + TextInferenceSupport.listWithLimit(
                    items, MAX_SHOWN_IN_INFERRED_TEXT));
        }
    }
}
