
package com.documentation.generator.service;

import com.documentation.generator.exception.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DocumentationDeliveryService {

    private final RestClient restClient;

    private final DocumentationStorageService storageService;

    private final ObjectMapper mapper;

    @Value("${documentation.delivery.enabled:false}")
    private boolean enabled;

    @Value("${documentation.delivery.endpoint:}")
    private String targetUrl;

    public DocumentationDeliveryService(
            RestClient restClient,
            DocumentationStorageService storageService,
            ObjectMapper mapper) {

        this.restClient = restClient;
        this.storageService = storageService;
        this.mapper = mapper;
    }

    public void deliver(String documentId) {

        if (!enabled || targetUrl == null || targetUrl.isBlank()) {

            throw new ApiException(
                "Documentation delivery is not configured"
            );
        }

        try {

            byte[] document =
                    storageService.read(documentId);

            restClient.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(document)
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception exception) {

            throw new ApiException(
                "Unable to deliver documentation: "
                        + exception.getMessage()
            );
        }
    }
}