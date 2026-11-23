package com.documentation.generator.service;

import org.springframework.http.HttpHeaders;

import java.net.URI;

/**
 * Applies a bearer token to outgoing requests, but only to requests that
 * target the same origin (scheme + host + port) as the URL the user
 * supplied, so credentials are never leaked to third-party hosts.
 */
public final class BearerAuthSupport {

    private BearerAuthSupport() {
    }

    public static void apply(
            HttpHeaders headers,
            String token,
            URI targetUri,
            URI credentialOrigin
    ) {

        if (token == null || token.isBlank()) {
            return;
        }

        if (!isSameOrigin(targetUri, credentialOrigin)) {
            return;
        }

        headers.setBearerAuth(token);
    }

    public static boolean isSameOrigin(URI first, URI second) {

        if (first == null || second == null
                || first.getScheme() == null || second.getScheme() == null
                || first.getHost() == null || second.getHost() == null) {
            return false;
        }

        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {

        if (uri.getPort() != -1) {
            return uri.getPort();
        }

        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
