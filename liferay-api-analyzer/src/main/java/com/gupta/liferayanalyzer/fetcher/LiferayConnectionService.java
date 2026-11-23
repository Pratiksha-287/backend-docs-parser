package com.gupta.liferayanalyzer.fetcher;

import tools.jackson.databind.JsonNode;
import com.gupta.liferayanalyzer.config.LiferayProperties;
import com.gupta.liferayanalyzer.exception.LiferayApiException;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class LiferayConnectionService {

    private final WebClient webClient;
    private final LiferayProperties properties;
    private final LiferayTokenService tokenService;

    public LiferayConnectionService(
            WebClient liferayWebClient,
            LiferayProperties properties,
            LiferayTokenService tokenService) {

        this.webClient = liferayWebClient;
        this.properties = properties;
        this.tokenService = tokenService;
    }

    public JsonNode fetchCatalog() {

        if (!tokenService.isEnabled()) {
            // No OAuth 2 client configured: legacy Basic auth (if any)
            // is already applied by the WebClient, or the call is anonymous.
            return requestCatalog(null);
        }

        try {
            return requestCatalog(tokenService.getAccessToken());

        } catch (LiferayApiException ex) {

            if (ex.getHttpStatus() != 401) {
                throw ex;
            }

            // Token may have been revoked or expired early: retry once.
            tokenService.invalidate();

            return requestCatalog(tokenService.getAccessToken());
        }
    }

    private JsonNode requestCatalog(String bearerToken) {

        String catalogPath =
                properties.getDiscovery().getCatalogPath();

        return webClient.get()
                .uri(catalogPath)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (bearerToken != null) {
                        headers.setBearerAuth(bearerToken);
                    }
                })
                .retrieve()

                .onStatus(
                        HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new LiferayApiException(
                                        "Liferay returned HTTP "
                                                + response.statusCode().value()
                                                + ". Response: "
                                                + truncate(body),
                                        response.statusCode().value()
                                ))
                )

                .bodyToMono(JsonNode.class)

                .block(properties.getRequestTimeout());
    }

    private String truncate(String body) {

        if (body == null || body.isBlank()) {
            return "No response body";
        }

        return body.length() > 500
                ? body.substring(0, 500)
                : body;
    }
}
