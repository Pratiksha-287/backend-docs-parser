package com.gupta.liferayanalyzer.fetcher;

import com.gupta.liferayanalyzer.config.LiferayProperties;
import com.gupta.liferayanalyzer.exception.LiferayApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

/**
 * Obtains and caches an OAuth 2.0 access token from Liferay using the
 * client_credentials grant (client id + client secret).
 */
@Service
public class LiferayTokenService {

    private static final Logger log =
            LoggerFactory.getLogger(LiferayTokenService.class);

    private static final long DEFAULT_LIFETIME_SECONDS = 600;

    private final WebClient webClient;
    private final LiferayProperties properties;

    private String accessToken;
    private Instant refreshAt = Instant.EPOCH;

    public LiferayTokenService(
            WebClient liferayWebClient,
            LiferayProperties properties) {

        this.webClient = liferayWebClient;
        this.properties = properties;
    }

    /** True when a client id is configured, so bearer tokens are used. */
    public boolean isEnabled() {
        return properties.getOauth2().isConfigured();
    }

    /**
     * Returns a valid access token, requesting a new one when there is
     * none yet or the cached one is about to expire.
     */
    public synchronized String getAccessToken() {

        if (!isEnabled()) {
            throw new IllegalStateException(
                    "OAuth 2 client id is not configured "
                            + "(liferay.oauth2.client-id)."
            );
        }

        if (accessToken == null || !Instant.now().isBefore(refreshAt)) {
            requestNewToken();
        }

        return accessToken;
    }

    /** Forget the cached token, e.g. after Liferay answered 401. */
    public synchronized void invalidate() {
        accessToken = null;
        refreshAt = Instant.EPOCH;
    }

    private void requestNewToken() {

        LiferayProperties.OAuth2 oauth2 = properties.getOauth2();

        String clientSecret = oauth2.getClientSecret() == null
                ? ""
                : oauth2.getClientSecret();

        boolean basic = "basic".equalsIgnoreCase(
                oauth2.getClientAuthentication()
        );

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        if (!basic) {
            form.add("client_id", oauth2.getClientId());
            form.add("client_secret", clientSecret);
        }

        if (oauth2.getScope() != null && !oauth2.getScope().isBlank()) {
            form.add("scope", oauth2.getScope().trim());
        }

        log.debug(
                "Requesting Liferay access token from {} (client {})",
                oauth2.getTokenPath(),
                oauth2.getClientId()
        );

        JsonNode response = webClient.post()
                .uri(oauth2.getTokenPath())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (basic) {
                        headers.setBasicAuth(
                                oauth2.getClientId(),
                                clientSecret
                        );
                    }
                })
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        clientResponse -> clientResponse
                                .bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new LiferayApiException(
                                        "Liferay token endpoint returned HTTP "
                                                + clientResponse.statusCode()
                                                        .value()
                                                + ". Check client id/secret, "
                                                + "that the 'Client Credentials' "
                                                + "grant is enabled on the "
                                                + "OAuth 2 application, and "
                                                + "its 'Client Credentials User'. "
                                                + "Response: " + truncate(body),
                                        clientResponse.statusCode().value()
                                ))
                )
                .bodyToMono(JsonNode.class)
                .block(properties.getRequestTimeout());

        JsonNode tokenNode =
                response == null ? null : response.get("access_token");

        if (tokenNode == null || !tokenNode.isString()
                || tokenNode.stringValue().isBlank()) {

            throw new LiferayApiException(
                    "Liferay token endpoint did not return an access_token."
            );
        }

        long lifetimeSeconds = readLifetimeSeconds(response.get("expires_in"));

        Duration lifetime = Duration.ofSeconds(lifetimeSeconds)
                .minus(oauth2.getExpirySkew());

        if (lifetime.isNegative() || lifetime.isZero()) {
            lifetime = Duration.ofSeconds(1);
        }

        accessToken = tokenNode.stringValue();
        refreshAt = Instant.now().plus(lifetime);

        log.debug("Obtained Liferay access token, valid for ~{}s",
                lifetime.toSeconds());
    }

    private long readLifetimeSeconds(JsonNode expiresIn) {

        if (expiresIn == null) {
            return DEFAULT_LIFETIME_SECONDS;
        }

        try {
            long value = expiresIn.isNumber()
                    ? expiresIn.longValue()
                    : Long.parseLong(expiresIn.asString().trim());

            return value > 0 ? value : DEFAULT_LIFETIME_SECONDS;

        } catch (RuntimeException ex) {
            return DEFAULT_LIFETIME_SECONDS;
        }
    }

    private String truncate(String body) {

        if (body == null || body.isBlank()) {
            return "No response body";
        }

        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
