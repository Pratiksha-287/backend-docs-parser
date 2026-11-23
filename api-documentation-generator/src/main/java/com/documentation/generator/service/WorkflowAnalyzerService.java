package com.documentation.generator.service;

import com.documentation.generator.model.DocumentationDocument.WorkflowCandidate;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds multi-step usage patterns.
 *
 * Spring Boot specifications almost never declare OpenAPI response links,
 * so besides explicit links this analyzer infers, from path conventions:
 *  - CRUD lifecycle of a resource        (INFERRED_CRUD)
 *  - nested resources                    (INFERRED_NESTED_RESOURCE)
 *  - login / token endpoints             (INFERRED_AUTHENTICATION)
 *
 * Operations are written as "METHOD /path", the same identifiers that the
 * relationships use. Inferred workflows are never HIGH confidence unless
 * the full create / read / change-or-delete cycle is present.
 */
@Service
public class WorkflowAnalyzerService {

    private static final Pattern AUTH_SEGMENT = Pattern.compile(
            "login|signin|sign-in|authenticate|auth|token|refresh|oauth",
            Pattern.CASE_INSENSITIVE);

    public List<WorkflowCandidate> analyze(OpenAPI openAPI) {

        List<WorkflowCandidate> workflows = new ArrayList<>();

        if (openAPI.getPaths() == null) {
            return workflows;
        }

        // "METHOD /path" for every operation, keyed by path
        Map<String, Map<String, Operation>> operationsByPath =
                new LinkedHashMap<>();

        Map<String, String> idByOperationId = new LinkedHashMap<>();

        openAPI.getPaths().forEach((path, item) -> {

            if (item == null) {
                return;
            }

            Map<String, Operation> methods = new LinkedHashMap<>();

            for (Map.Entry<PathItem.HttpMethod, Operation> entry :
                    item.readOperationsMap().entrySet()) {

                methods.put(entry.getKey().name(), entry.getValue());

                if (entry.getValue().getOperationId() != null) {
                    idByOperationId.put(
                            entry.getValue().getOperationId(),
                            entry.getKey().name() + " " + path);
                }
            }

            operationsByPath.put(path, methods);
        });

        addExplicitLinks(operationsByPath, idByOperationId, workflows);
        addCrudWorkflows(operationsByPath, workflows);
        addAuthentication(operationsByPath, workflows);

        return workflows;
    }

    // ------------------------------------------------------------------

    private void addExplicitLinks(
            Map<String, Map<String, Operation>> operationsByPath,
            Map<String, String> idByOperationId,
            List<WorkflowCandidate> workflows) {

        operationsByPath.forEach((path, methods) ->
                methods.forEach((method, operation) -> {

            if (operation.getResponses() == null) {
                return;
            }

            String source = method + " " + path;

            operation.getResponses().forEach((status, response) -> {

                if (response == null || response.getLinks() == null) {
                    return;
                }

                response.getLinks().forEach((linkName, link) -> {

                    if (link == null) {
                        return;
                    }

                    String target = link.getOperationId() != null
                            ? idByOperationId.getOrDefault(
                                    link.getOperationId(),
                                    link.getOperationId())
                            : link.getOperationRef();

                    if (target == null) {
                        return;
                    }

                    workflows.add(new WorkflowCandidate(
                            linkName,
                            "After " + source + " (" + status
                                    + ") continue with " + target
                                    + " (declared as an OpenAPI link)",
                            List.of(source, target),
                            "SPECIFICATION_DEFINED_LINK",
                            "HIGH"));
                });
            });
        }));
    }

    // ------------------------------------------------------------------

    private void addCrudWorkflows(
            Map<String, Map<String, Operation>> operationsByPath,
            List<WorkflowCandidate> workflows) {

        // collection path -> role -> "METHOD /path"
        Map<String, Map<String, String>> resources = new LinkedHashMap<>();

        operationsByPath.forEach((path, methods) -> {

            List<String> segments = segments(path);

            if (segments.isEmpty()) {
                return;
            }

            boolean item = TextInferenceSupport.isParam(
                    segments.get(segments.size() - 1));

            String collection = item
                    ? joinPath(segments.subList(0, segments.size() - 1))
                    : joinPath(segments);

            if (collection.isEmpty()) {
                return;
            }

            Map<String, String> roles = resources.computeIfAbsent(
                    collection, key -> new LinkedHashMap<>());

            methods.keySet().forEach(method -> {

                String role = roleOf(method, item);

                if (role != null) {
                    roles.putIfAbsent(role, method + " " + path);
                }
            });
        });

        resources.forEach((collection, roles) -> {

            // a lifecycle needs a way to create and a way to use/change
            if (!roles.containsKey("create") || roles.size() < 3) {
                return;
            }

            List<String> operations = new ArrayList<>();

            List<String> order = List.of(
                    "create", "list", "get", "update", "patch", "delete");

            List<String> labels = new ArrayList<>();

            for (String role : order) {

                if (roles.containsKey(role)) {
                    operations.add(roles.get(role));
                    labels.add(role);
                }
            }

            List<String> segments = segments(collection);

            String resource = TextInferenceSupport.humanize(
                    segments.get(segments.size() - 1));

            boolean complete = roles.containsKey("get")
                    && (roles.containsKey("update")
                    || roles.containsKey("patch")
                    || roles.containsKey("delete"));

            workflows.add(new WorkflowCandidate(
                    "Manage " + resource,
                    "Typical lifecycle of " + resource + ": "
                            + String.join(" -> ", labels)
                            + ". Create first, then read, change or "
                            + "remove using the identifier returned.",
                    operations,
                    "INFERRED_CRUD",
                    complete ? "HIGH" : "MEDIUM"));

            addNested(collection, roles, resources, resource, workflows);
        });
    }

    /** /users/{userId}/orders is a child of /users. */
    private void addNested(
            String collection,
            Map<String, String> roles,
            Map<String, Map<String, String>> resources,
            String resource,
            List<WorkflowCandidate> workflows) {

        List<String> segments = segments(collection);

        int n = segments.size();

        if (n < 3 || !TextInferenceSupport.isParam(segments.get(n - 2))) {
            return;
        }

        String parentCollection = joinPath(segments.subList(0, n - 2));

        Map<String, String> parent = resources.get(parentCollection);

        if (parent == null) {
            return;
        }

        List<String> operations = new ArrayList<>();

        for (String role : List.of("create", "get")) {

            if (parent.containsKey(role)) {
                operations.add(parent.get(role));
            }
        }

        if (operations.isEmpty()) {
            return;
        }

        for (String role : List.of("create", "list")) {

            if (roles.containsKey(role)) {
                operations.add(roles.get(role));
            }
        }

        String parentName = TextInferenceSupport.singular(
                TextInferenceSupport.humanize(
                        segments.get(n - 3)));

        workflows.add(new WorkflowCandidate(
                TextInferenceSupport.capitalize(resource) + " of "
                        + TextInferenceSupport.withArticle(parentName),
                resource + " only exist inside "
                        + TextInferenceSupport.withArticle(parentName)
                        + ": create or fetch the parent first, then use "
                        + "its identifier in the path.",
                operations,
                "INFERRED_NESTED_RESOURCE",
                "MEDIUM"));
    }

    private static String roleOf(String method, boolean item) {

        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> item ? null : "create";
            case "GET" -> item ? "get" : "list";
            case "PUT" -> item ? "update" : null;
            case "PATCH" -> item ? "patch" : null;
            case "DELETE" -> item ? "delete" : null;
            default -> null;
        };
    }

    // ------------------------------------------------------------------

    private void addAuthentication(
            Map<String, Map<String, Operation>> operationsByPath,
            List<WorkflowCandidate> workflows) {

        List<String> operations = new ArrayList<>();

        operationsByPath.forEach((path, methods) -> {

            List<String> segments = segments(path);

            if (segments.isEmpty() || !methods.containsKey("POST")) {
                return;
            }

            String last = segments.get(segments.size() - 1);

            if (!TextInferenceSupport.isParam(last)
                    && AUTH_SEGMENT.matcher(last).matches()) {

                operations.add("POST " + path);
            }
        });

        if (!operations.isEmpty()) {

            workflows.add(new WorkflowCandidate(
                    "Authenticate",
                    "Obtain or refresh credentials here before calling "
                            + "endpoints that declare authentication.",
                    operations,
                    "INFERRED_AUTHENTICATION",
                    "MEDIUM"));
        }
    }

    // ------------------------------------------------------------------

    /** Path segments without the leading api / version prefix. */
    private static List<String> segments(String path) {

        List<String> result = new ArrayList<>();

        for (String segment : path.split("/")) {

            if (segment.isBlank()) {
                continue;
            }

            if (result.isEmpty()
                    && (segment.equalsIgnoreCase("api")
                    || segment.matches("(?i)v\\d+(\\.\\d+)*"))) {
                continue;
            }

            result.add(segment);
        }

        return result;
    }

    private static String joinPath(List<String> segments) {
        return segments.isEmpty() ? "" : "/" + String.join("/", segments);
    }
}
