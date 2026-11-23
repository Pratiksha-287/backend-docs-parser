package com.documentation.generator.service;

import com.documentation.generator.exception.ApiException;
import com.documentation.generator.model.SpecificationSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
public class SpecificationFetchService {

    private final RestClient restClient;

    private final int maxSize;

    public SpecificationFetchService(
            RestClient restClient,
            @Value("${documentation.source.max-size-bytes:10485760}")
            int maxSize) {

        this.restClient = restClient;
        this.maxSize = maxSize;
    }

    public SpecificationSource fetchFromUrl(
            String url,
            String projectName,
            String bearerToken,
            URI credentialOrigin) {

        URI specificationUri = validateUrl(url);

        try {

            byte[] content = restClient.get()
                    .uri(specificationUri)
                    .accept(
                            MediaType.APPLICATION_JSON,
                            MediaType.APPLICATION_YAML,
                            MediaType.TEXT_PLAIN,
                            MediaType.ALL
                    )
                    .headers(headers ->
                            BearerAuthSupport.apply(
                                    headers,
                                    bearerToken,
                                    specificationUri,
                                    credentialOrigin
                            )
                    )
                    .retrieve()
                    .body(byte[].class);

            if (content == null || content.length == 0) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "The source returned an empty specification: " + url
                );
            }

            if (content.length > maxSize) {
                throw new ApiException(
                        "Specification exceeds the " + maxSize
                                + " byte limit: " + url
                );
            }

            return new SpecificationSource(
                    new String(content, StandardCharsets.UTF_8),
                    url,
                    "URL",
                    projectName
            );

        } catch (ApiException exception) {

            throw exception;

        } catch (Exception exception) {

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to fetch specification " + url + ": "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    public SpecificationSource fetchFromFile(
            byte[] content,
            String filename,
            String projectName) {

        if (content == null || content.length == 0) {
            throw new ApiException("Uploaded file is empty");
        }

        if (content.length > maxSize) {
            throw new ApiException(
                    "Uploaded file exceeds the " + maxSize + " byte limit"
            );
        }

        if (filename == null) {
            filename = "specification.yaml";
        }

        String lowerName = filename.toLowerCase();

        if (!lowerName.endsWith(".json")
                && !lowerName.endsWith(".yaml")
                && !lowerName.endsWith(".yml")) {

            throw new ApiException(
                    "Only JSON and YAML files are supported"
            );
        }

        return new SpecificationSource(
                new String(content, StandardCharsets.UTF_8),
                filename,
                "FILE",
                projectName
        );
    }

    private URI validateUrl(String url) {

        URI uri;

        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException exception) {
            throw new ApiException("Invalid source URL: " + url, exception);
        }

        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {

            throw new ApiException("Only HTTP and HTTPS are permitted");
        }

        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new ApiException("Invalid source URL: " + url);
        }

        return uri;
    }
}
