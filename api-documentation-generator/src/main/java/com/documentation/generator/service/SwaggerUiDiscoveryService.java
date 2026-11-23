
package com.documentation.generator.service;

import com.documentation.generator.exception.SwaggerUiDiscoveryException;
import com.documentation.generator.model.DiscoveredApiSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SwaggerUiDiscoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(SwaggerUiDiscoveryService.class);

    // Large third-party Swagger UI bundles never hold the API
    // configuration, so downloading them only wastes time and memory.
    private static final Pattern VENDOR_SCRIPT_PATTERN =
            Pattern.compile(
                    "swagger-ui(-bundle|-standalone-preset)?(\\.[\\w-]+)*\\.js$"
                            + "|swagger-ui-es-bundle(-core)?\\.js$",
                    Pattern.CASE_INSENSITIVE
            );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private static final Pattern CONFIG_URL_PATTERN =
            Pattern.compile(
                    "\\bconfigUrl['\"]?\\s*[:=]\\s*(['\"])(.*?)\\1",
                    Pattern.DOTALL
            );

    private static final Pattern URL_PATTERN =
            Pattern.compile(
                    "\\burl['\"]?\\s*:\\s*(['\"])(.*?)\\1",
                    Pattern.DOTALL
            );

    private static final Pattern NAME_PATTERN =
            Pattern.compile(
                    "\\bname['\"]?\\s*:\\s*(['\"])(.*?)\\1",
                    Pattern.DOTALL
            );

    public SwaggerUiDiscoveryService(
            RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public List<DiscoveredApiSpec> discover(
            String sourceUrl,
            String bearerToken
    ) {

        URI pageUri = validateHttpUrl(sourceUrl);

        String content = fetch(pageUri, bearerToken, pageUri);

        // Direct OpenAPI JSON/YAML URLs are still supported.
        if (!isHtml(content)) {

            return List.of(
                    new DiscoveredApiSpec(
                            "Default API",
                            pageUri.toString()
                    )
            );
        }

        Document document = Jsoup.parse(content, pageUri.toString());

        StringBuilder scripts = new StringBuilder();

        // Read inline scripts and inspect same-origin external scripts.
        for (Element script : document.select("script")) {

            scripts.append(script.data()).append("\n");

            if (!script.hasAttr("src")) {
                continue;
            }

            String scriptPath = script.attr("src").trim();

            try {

                URI scriptUri = pageUri.resolve(scriptPath);

                validateHttpUrl(scriptUri.toString());

                // Never download scripts from other hosts; the page
                // configuration is always served by the Swagger UI host.
                if (!BearerAuthSupport.isSameOrigin(scriptUri, pageUri)
                        || isVendorScript(scriptUri)) {
                    continue;
                }

                scripts.append(fetch(scriptUri, bearerToken, pageUri))
                        .append("\n");

            } catch (Exception exception) {

                // An unrelated or unavailable script should not
                // prevent discovery of the Swagger configuration.
                log.warn("Unable to inspect script {}: {}",
                        scriptPath, exception.getMessage());
            }
        }

        String scriptContent = scripts.toString();

        // External configuration takes priority.
        String configUrl = extractConfigUrl(pageUri, scriptContent);

        List<DiscoveredApiSpec> discovered;

        if (configUrl != null && !configUrl.isBlank()) {

            URI configUri = pageUri.resolve(configUrl.trim());

            validateHttpUrl(configUri.toString());

            String configJson = fetch(configUri, bearerToken, pageUri);

            discovered = parseJsonConfiguration(configJson, configUri);

        } else {

            discovered = parseInlineConfiguration(scriptContent, pageUri);
        }

        if (discovered.isEmpty()) {
            discovered = discoverFromWellKnownLocations(
                    pageUri, bearerToken);
        }

        if (discovered.isEmpty()) {

            log.warn("No configuration found in {} ({} chars of script "
                            + "inspected)", pageUri, scriptContent.length());

            throw new SwaggerUiDiscoveryException(
                    "No API specifications discovered. "
                            + "Could not find a Swagger UI url, urls, "
                            + "or configUrl configuration. If the Swagger "
                            + "UI needs authentication, send the "
                            + "'authentication' block, or pass the direct "
                            + "OpenAPI JSON URL as sourceUrl."
            );
        }

        // Remove duplicate URLs while preserving their original order.
        Map<String, DiscoveredApiSpec> unique = new LinkedHashMap<>();

        for (DiscoveredApiSpec spec : discovered) {
            unique.putIfAbsent(spec.specificationUrl(), spec);
        }

        log.info("Discovered {} API specification(s) at {}",
                unique.size(), pageUri);

        return new ArrayList<>(unique.values());
    }

    /**
     * Last resort: try the standard springdoc / Swagger UI locations
     * relative to the page (e.g. .../swagger-ui/index.html ->
     * .../v3/api-docs/swagger-config and .../v3/api-docs).
     */
    private List<DiscoveredApiSpec> discoverFromWellKnownLocations(
            URI pageUri,
            String bearerToken
    ) {

        for (String candidate : List.of(
                "../v3/api-docs/swagger-config",
                "../swagger-config")) {

            try {

                URI configUri = pageUri.resolve(candidate);

                String body = fetch(configUri, bearerToken, pageUri);

                if (isHtml(body)) {
                    continue;
                }

                List<DiscoveredApiSpec> found =
                        parseJsonConfiguration(body, configUri);

                if (!found.isEmpty()) {
                    return found;
                }

            } catch (Exception exception) {
                log.debug("Well-known location {} failed: {}",
                        candidate, exception.getMessage());
            }
        }

        for (String candidate : List.of(
                "../v3/api-docs",
                "../v2/api-docs")) {

            try {

                URI specUri = pageUri.resolve(candidate);

                String body = fetch(specUri, bearerToken, pageUri);

                if (!isHtml(body)
                        && (body.contains("\"openapi\"")
                        || body.contains("\"swagger\""))) {

                    return List.of(
                            new DiscoveredApiSpec(
                                    "Default API",
                                    specUri.toString()
                            )
                    );
                }

            } catch (Exception exception) {
                log.debug("Well-known location {} failed: {}",
                        candidate, exception.getMessage());
            }
        }

        return new ArrayList<>();
    }

    private boolean isHtml(String content) {

        // OpenAPI JSON starts with '{' and YAML with a key, never '<'.
        return content.stripLeading().startsWith("<");
    }

    private boolean isVendorScript(URI scriptUri) {

        String path = scriptUri.getPath();

        if (path == null) {
            return false;
        }

        return VENDOR_SCRIPT_PATTERN.matcher(path).find();
    }

    private List<DiscoveredApiSpec> parseJsonConfiguration(
            String configJson,
            URI configUri
    ) {

        try {

            JsonNode root = objectMapper.readTree(configJson);

            List<DiscoveredApiSpec> result =
                    new ArrayList<>();

            JsonNode urls = root.path("urls");

            if (urls.isArray()) {

                for (JsonNode item : urls) {

                    String url = getText(item, "url");

                    String name = getText(item, "name");

                    if (url == null || url.isBlank()) {
                        continue;
                    }

                    result.add(
                            createSpec(
                                    name,
                                    url,
                                    configUri
                            )
                    );
                }
            }

            // Single API configuration.
            if (result.isEmpty()) {

                String url = getText(root, "url");

                if (url != null && !url.isBlank()) {

                    result.add(
                            createSpec(
                                    "Default API",
                                    url,
                                    configUri
                            )
                    );
                }
            }

            return result;

        } catch (Exception exception) {

            throw new SwaggerUiDiscoveryException(
                    "Unable to parse Swagger UI configuration: "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    private List<DiscoveredApiSpec> parseInlineConfiguration(
            String scripts,
            URI pageUri
    ) {

        List<DiscoveredApiSpec> result =
                new ArrayList<>();

        // First handle multiple APIs.
        String urlsArray = extractArray(
                scripts,
                "urls"
        );

        if (urlsArray != null) {

            List<String> objects =
                    extractObjects(urlsArray);

            for (String object : objects) {

                String url = extractProperty(
                        URL_PATTERN,
                        object
                );

                String name = extractProperty(
                        NAME_PATTERN,
                        object
                );

                if (url != null && !url.isBlank()) {

                    result.add(
                            createSpec(
                                    name,
                                    url,
                                    pageUri
                            )
                    );
                }
            }
        }

        // Fall back to a single API.
        if (result.isEmpty()) {

            String url = extractProperty(
                    URL_PATTERN,
                    scripts
            );

            if (url != null && !url.isBlank()) {

                result.add(
                        createSpec(
                                "Default API",
                                url,
                                pageUri
                        )
                );
            }
        }

        return result;
    }

    private String extractConfigUrl(
            URI pageUri,
            String scripts
    ) {

        // Check the page query parameter.
        String query = pageUri.getRawQuery();

        if (query != null) {

            for (String parameter : query.split("&")) {

                String[] parts = parameter.split("=", 2);

                if (parts.length == 2) {

                    String key = URLDecoder.decode(
                            parts[0],
                            StandardCharsets.UTF_8
                    );

                    if ("configUrl".equals(key)) {

                        return URLDecoder.decode(
                                parts[1],
                                StandardCharsets.UTF_8
                        );
                    }
                }
            }
        }

        // Check inline JavaScript configuration.
        return extractProperty(
                CONFIG_URL_PATTERN,
                scripts
        );
    }

    private String extractArray(
            String source,
            String property
    ) {

        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(property) + "['\"]?\\s*:\\s*\\[",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(source);

        if (!matcher.find()) {
            return null;
        }

        int start = matcher.end();

        int depth = 1;

        char quote = 0;

        boolean escaped = false;

        for (int i = start; i < source.length(); i++) {

            char current = source.charAt(i);

            if (quote != 0) {

                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }

                continue;
            }

            if (current == '\'' || current == '"') {

                quote = current;

            } else if (current == '[') {

                depth++;

            } else if (current == ']') {

                depth--;

                if (depth == 0) {

                    return source.substring(
                            start,
                            i
                    );
                }
            }
        }

        throw new SwaggerUiDiscoveryException(
                "Invalid Swagger UI urls array."
        );
    }

    private List<String> extractObjects(String source) {

        List<String> objects = new ArrayList<>();

        int depth = 0;

        int start = -1;

        char quote = 0;

        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {

            char current = source.charAt(i);

            if (quote != 0) {

                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }

                continue;
            }

            if (current == '\'' || current == '"') {

                quote = current;

            } else if (current == '{') {

                if (depth == 0) {
                    start = i;
                }

                depth++;

            } else if (current == '}') {

                depth--;

                if (depth == 0 && start >= 0) {

                    objects.add(
                            source.substring(
                                    start,
                                    i + 1
                            )
                    );

                    start = -1;
                }
            }
        }

        return objects;
    }

    private String extractProperty(
            Pattern pattern,
            String source
    ) {

        Matcher matcher = pattern.matcher(source);

        if (!matcher.find()) {
            return null;
        }

        return matcher.group(2);
    }

    private DiscoveredApiSpec createSpec(
            String name,
            String specificationUrl,
            URI baseUri
    ) {

        try {

            URI resolvedUri = baseUri.resolve(
                    specificationUrl.trim()
            );

            validateHttpUrl(
                    resolvedUri.toString()
            );

            String finalName =
                    name == null || name.isBlank()
                            ? "Unnamed API"
                            : name.trim();

            return new DiscoveredApiSpec(
                    finalName,
                    resolvedUri.toString()
            );

        } catch (Exception exception) {

            throw new SwaggerUiDiscoveryException(
                    "Invalid specification URL: "
                            + specificationUrl,
                    exception
            );
        }
    }

    private URI validateHttpUrl(String url) {

        try {

            URI uri = URI.create(url.trim());

            String scheme = uri.getScheme();

            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {

                throw new IllegalArgumentException(
                        "Only valid HTTP and HTTPS URLs are supported."
                );
            }

            return uri;

        } catch (Exception exception) {

            throw new SwaggerUiDiscoveryException(
                    "Invalid URL: " + url,
                    exception
            );
        }
    }

    private String fetch(
            URI uri,
            String bearerToken,
            URI credentialOrigin
    ) {

        try {

            String response = restClient.get()
                    .uri(uri)
                    .headers(headers ->
                            BearerAuthSupport.apply(
                                    headers,
                                    bearerToken,
                                    uri,
                                    credentialOrigin
                            )
                    )
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {

                throw new SwaggerUiDiscoveryException(
                        "Empty response received from: " + uri
                );
            }

            return response;

        } catch (SwaggerUiDiscoveryException exception) {

            throw exception;

        } catch (Exception exception) {

            throw new SwaggerUiDiscoveryException(
                    "Unable to fetch URL: " + uri
                            + ". Reason: " + exception.getMessage(),
                    exception
            );
        }
    }

    private String getText(
            JsonNode node,
            String field
    ) {

        JsonNode value = node.get(field);

        return value != null && value.isTextual()
                ? value.asText()
                : null;
    }
}
