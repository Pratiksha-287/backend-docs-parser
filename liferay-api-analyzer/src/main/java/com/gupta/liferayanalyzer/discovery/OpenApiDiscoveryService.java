
package com.gupta.liferayanalyzer.discovery;

import com.gupta.liferayanalyzer.model.DiscoveredApi;
import com.gupta.liferayanalyzer.model.DiscoveredApiResponse;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

@Service
public class OpenApiDiscoveryService {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get",
            "post",
            "put",
            "patch",
            "delete",
            "options",
            "head",
            "trace"
    );

    private static final String PATH_SEPARATOR = "/";

    private static final Pattern VERSION_SEGMENT_PATTERN =
            Pattern.compile("v\\d++");

    private static final Pattern VERSION_NUMBER_SEGMENT_PATTERN =
            Pattern.compile("\\d++");

    private static final Pattern PATH_VERSION_PATTERN =
            Pattern.compile("v\\d++(?:\\.\\d++)*+");

    /*
     * Matches tags such as:
     *
     * HeadlessAdminUser.v1.0.Account
     * HeadlessDelivery.v1.0.StructuredContent
     *
     * API name  : HeadlessAdminUser
     * Version   : v1.0
     * Resource  : Account
     */
    public DiscoveredApiResponse discover(JsonNode openApiDocument) {

        validateDocument(openApiDocument);

        String documentTitle = getText(
                openApiDocument.path("info"),
                "title"
        );

        String openApiVersion = getText(
                openApiDocument,
                "openapi"
        );

        JsonNode pathsNode = openApiDocument.get("paths");

        Map<ApiIdentity, ApiAccumulator> discoveredApis =
                new LinkedHashMap<>();

        int totalOperations = collectOperations(pathsNode, discoveredApis);

        List<DiscoveredApi> apis = discoveredApis.entrySet()
                .stream()
                .map(entry -> entry.getKey().toDiscoveredApi(
                        entry.getValue()
                ))
                .sorted(
                        Comparator.comparing(DiscoveredApi::apiName)
                                .thenComparing(DiscoveredApi::version)
                )
                .toList();

        return new DiscoveredApiResponse(
                documentTitle,
                openApiVersion,
                apis.size(),
                pathsNode.size(),
                totalOperations,
                apis
        );
    }

    private int collectOperations(
            JsonNode pathsNode,
            Map<ApiIdentity, ApiAccumulator> discoveredApis
    ) {
        int totalOperations = 0;

        for (String path : pathsNode.propertyNames()) {
            JsonNode pathItem = pathsNode.get(path);

            if (pathItem != null && pathItem.isObject()) {
                totalOperations += collectPathOperations(
                        path,
                        pathItem,
                        discoveredApis
                );
            }
        }

        return totalOperations;
    }

    private int collectPathOperations(
            String path,
            JsonNode pathItem,
            Map<ApiIdentity, ApiAccumulator> discoveredApis
    ) {
        ApiIdentity fallbackIdentity = deriveIdentityFromPath(path);
        int totalOperations = 0;

        for (String method : pathItem.propertyNames()) {
            totalOperations += collectOperation(
                    path,
                    method,
                    pathItem,
                    fallbackIdentity,
                    discoveredApis
            );
        }

        return totalOperations;
    }

    private int collectOperation(
            String path,
            String method,
            JsonNode pathItem,
            ApiIdentity fallbackIdentity,
            Map<ApiIdentity, ApiAccumulator> discoveredApis
    ) {
        String normalizedMethod = method.toLowerCase(Locale.ROOT);
        JsonNode operationNode = pathItem.get(normalizedMethod);

        if (!HTTP_METHODS.contains(normalizedMethod)
                || operationNode == null
                || !operationNode.isObject()) {
            return 0;
        }

        List<TagInformation> parsedTags = extractTags(operationNode);

        if (parsedTags.isEmpty()) {
            getOrCreateAccumulator(discoveredApis, fallbackIdentity)
                    .addOperation(path, normalizedMethod, null);
        } else {
            addTaggedOperation(
                    path,
                    normalizedMethod,
                    fallbackIdentity,
                    parsedTags,
                    discoveredApis
            );
        }

        return 1;
    }

    private void addTaggedOperation(
            String path,
            String method,
            ApiIdentity fallbackIdentity,
            List<TagInformation> tags,
            Map<ApiIdentity, ApiAccumulator> discoveredApis
    ) {
        for (TagInformation tag : tags) {
            ApiIdentity identity = new ApiIdentity(
                    tag.apiName(),
                    tag.version(),
                    fallbackIdentity.basePath()
            );

            getOrCreateAccumulator(discoveredApis, identity)
                    .addOperation(path, method, tag.resource());
        }
    }

    private ApiAccumulator getOrCreateAccumulator(
            Map<ApiIdentity, ApiAccumulator> discoveredApis,
            ApiIdentity identity
    ) {
        return discoveredApis.computeIfAbsent(
                identity,
                key -> new ApiAccumulator()
        );
    }

    private void validateDocument(JsonNode document) {

        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException(
                    "Invalid OpenAPI document: expected a JSON object."
            );
        }

        JsonNode paths = document.get("paths");

        if (paths == null || !paths.isObject()) {
            throw new IllegalArgumentException(
                    "Invalid OpenAPI document: missing or invalid paths object."
            );
        }
    }

    private List<TagInformation> extractTags(JsonNode operationNode) {

        List<TagInformation> parsedTags = new ArrayList<>();

        JsonNode tagsNode = operationNode.get("tags");

        if (tagsNode == null || !tagsNode.isArray()) {
            return parsedTags;
        }

        for (JsonNode tagNode : tagsNode) {
            if (tagNode != null && tagNode.isString()) {
                parseTag(tagNode.stringValue().trim())
                        .ifPresent(parsedTags::add);
            }
        }

        return parsedTags;
    }

    private Optional<TagInformation> parseTag(String tag) {
        String[] segments = tag.split("\\.", -1);

        for (int versionStart = segments.length - 1;
                versionStart > 0;
                versionStart--) {
            if (VERSION_SEGMENT_PATTERN.matcher(segments[versionStart])
                    .matches()) {
                int versionEnd = versionStart + 1;

                while (versionEnd < segments.length
                        && VERSION_NUMBER_SEGMENT_PATTERN
                                .matcher(segments[versionEnd])
                                .matches()) {
                    versionEnd++;
                }

                String apiName = String.join(
                        ".",
                        Arrays.copyOfRange(segments, 0, versionStart)
                );
                String version = String.join(
                        ".",
                        Arrays.copyOfRange(segments, versionStart, versionEnd)
                );
                String resource = versionEnd == segments.length
                        ? null
                        : String.join(
                                ".",
                                Arrays.copyOfRange(
                                        segments,
                                        versionEnd,
                                        segments.length
                                )
                        );

                if (!apiName.isEmpty()
                        && (resource == null || !resource.isEmpty())) {
                    return Optional.of(
                            new TagInformation(apiName, version, resource)
                    );
                }
            }
        }

        return Optional.empty();
    }

    private ApiIdentity deriveIdentityFromPath(String path) {

        String[] segments = path.split(PATH_SEPARATOR);

        List<String> meaningfulSegments = new ArrayList<>();

        for (String segment : segments) {
            if (!segment.isBlank()) {
                meaningfulSegments.add(segment);
            }
        }

        if (meaningfulSegments.isEmpty()) {
            return new ApiIdentity(
                    "Unknown",
                    "unversioned",
                    PATH_SEPARATOR
            );
        }

        String apiName = meaningfulSegments.get(0);

        String version = "unversioned";

        String basePath = String.join(PATH_SEPARATOR, "", apiName);

        if (meaningfulSegments.size() > 1) {

            String possibleVersion = meaningfulSegments.get(1);

            if (PATH_VERSION_PATTERN.matcher(possibleVersion).matches()) {

                version = possibleVersion;

                basePath = String.join(
                        PATH_SEPARATOR,
                        "",
                        apiName,
                        version
                );
            }
        }

        return new ApiIdentity(
                apiName,
                version,
                basePath
        );
    }

    private String getText(JsonNode node, String fieldName) {

        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asString();
    }

    private record TagInformation(
            String apiName,
            String version,
            String resource
    ) {
    }

    private record ApiIdentity(
            String apiName,
            String version,
            String basePath
    ) {

        private DiscoveredApi toDiscoveredApi(
                ApiAccumulator accumulator
        ) {

            return new DiscoveredApi(
                    apiName,
                    version,
                    basePath,
                    accumulator.paths.size(),
                    accumulator.operations.size(),
                    List.copyOf(accumulator.resources),
                    List.copyOf(accumulator.httpMethods)
            );
        }
    }

    private static class ApiAccumulator {

        private final Set<String> paths = new TreeSet<>();

        private final Set<String> operations = new TreeSet<>();

        private final Set<String> resources = new TreeSet<>();

        private final Set<String> httpMethods = new TreeSet<>();

        private void addOperation(
                String path,
                String method,
                String resource
        ) {

            paths.add(path);

            operations.add(path + "::" + method);

            httpMethods.add(method.toUpperCase(Locale.ROOT));

            if (resource != null && !resource.isBlank()) {
                resources.add(resource);
            }
        }
    }
}
