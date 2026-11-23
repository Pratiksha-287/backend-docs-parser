package com.documentation.generator.controller;

import com.documentation.generator.dto.GenerateResponse;
import com.documentation.generator.dto.GenerateUrlRequest;
import com.documentation.generator.exception.ApiException;
import com.documentation.generator.model.CombinedDocumentationDocument;
import com.documentation.generator.model.DiscoveredApiSpec;
import com.documentation.generator.model.DocumentationDocument;
import com.documentation.generator.model.SpecificationSource;
import com.documentation.generator.service.DocumentationBuilderService;
import com.documentation.generator.service.DocumentationStorageService;
import com.documentation.generator.service.SpecificationFetchService;
import com.documentation.generator.service.SpecificationParserService;
import com.documentation.generator.service.SwaggerUiDiscoveryService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documentation")
public class DocumentationController {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentationController.class);

    private static final String BASE_PATH = "/api/v1/documentation/";

    private final SpecificationFetchService fetchService;
    private final SpecificationParserService parserService;
    private final DocumentationBuilderService builderService;
    private final DocumentationStorageService storageService;
    private final SwaggerUiDiscoveryService discoveryService;

    public DocumentationController(
            SpecificationFetchService fetchService,
            SpecificationParserService parserService,
            DocumentationBuilderService builderService,
            DocumentationStorageService storageService,
            SwaggerUiDiscoveryService discoveryService) {

        this.fetchService = fetchService;
        this.parserService = parserService;
        this.builderService = builderService;
        this.storageService = storageService;
        this.discoveryService = discoveryService;
    }

    // Generate documentation from an uploaded JSON/YAML specification
    @PostMapping(
            value = "/generate/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<GenerateResponse> generateFromFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectName") String projectName) {

        if (projectName == null || projectName.isBlank()
                || projectName.length() > 150) {

            throw new ApiException(
                    "projectName is required and must be at most "
                            + "150 characters"
            );
        }

        SpecificationSource source =
                fetchService.fetchFromFile(
                        getFileBytes(file),
                        file.getOriginalFilename() != null
                                ? file.getOriginalFilename()
                                : "specification.yaml",
                        projectName
                );

        DocumentationDocument document =
                buildDocumentation(source, true);

        String id = storageService.save(document);

        return ResponseEntity.ok(
                new GenerateResponse(id, downloadUrl(id),
                        document.statistics(), List.of())
        );
    }

    // Discover every API of a Swagger UI page and document all of them
    @PostMapping("/generate/url")
    public ResponseEntity<GenerateResponse> generateFromUrl(
            @Valid @RequestBody GenerateUrlRequest request) {

        String bearerToken = null;

        if (request.authentication() != null) {

            if (!"BEARER".equalsIgnoreCase(
                    request.authentication().type())) {

                throw new ApiException(
                        "Unsupported authentication type. Use BEARER."
                );
            }

            bearerToken = request.authentication().token();
        }

        URI credentialOrigin = URI.create(request.sourceUrl().trim());

        List<DiscoveredApiSpec> discoveredSpecs =
                discoveryService.discover(
                        request.sourceUrl(),
                        bearerToken
                );

        List<CombinedDocumentationDocument.ApiDocumentation> apiDocuments =
                new ArrayList<>();

        List<CombinedDocumentationDocument.FailedApi> failedApis =
                new ArrayList<>();

        int totalEndpoints = 0;
        int totalSchemas = 0;
        int totalModules = 0;
        int totalRelationships = 0;

        for (DiscoveredApiSpec spec : discoveredSpecs) {

            DocumentationDocument documentation;

            try {

                SpecificationSource source =
                        fetchService.fetchFromUrl(
                                spec.specificationUrl(),
                                request.projectName(),
                                bearerToken,
                                credentialOrigin
                        );

                // The notation legend is written once for the whole file.
                documentation = buildDocumentation(source, false);

            } catch (Exception exception) {

                // One unavailable API must not stop the others.
                log.warn("Skipping API '{}' ({}): {}",
                        spec.name(),
                        spec.specificationUrl(),
                        exception.getMessage());

                failedApis.add(
                        new CombinedDocumentationDocument.FailedApi(
                                spec.name(),
                                spec.specificationUrl(),
                                exception.getMessage()
                        )
                );

                continue;
            }

            apiDocuments.add(
                    new CombinedDocumentationDocument.ApiDocumentation(
                            spec.name(),
                            spec.specificationUrl(),
                            documentation
                    )
            );

            totalEndpoints += documentation.statistics().totalEndpoints();
            totalSchemas += documentation.statistics().totalSchemas();
            totalModules += documentation.statistics().totalModules();
            totalRelationships +=
                    documentation.statistics().totalRelationships();
        }

        if (apiDocuments.isEmpty()) {

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "None of the " + discoveredSpecs.size()
                            + " discovered APIs could be documented: "
                            + failedApis.stream()
                            .map(failed -> failed.name() + " -> "
                                    + failed.error())
                            .collect(Collectors.joining(" | "))
            );
        }

        DocumentationDocument.Statistics statistics =
                new DocumentationDocument.Statistics(
                        totalEndpoints,
                        totalSchemas,
                        totalModules,
                        totalRelationships
                );

        CombinedDocumentationDocument combinedDocument =
                new CombinedDocumentationDocument(
                        "2.0",
                        new CombinedDocumentationDocument.Metadata(
                                request.projectName(),
                                request.sourceUrl(),
                                "URL",
                                discoveredSpecs.size(),
                                apiDocuments.size(),
                                failedApis.size(),
                                builderService.conventions(),
                                Instant.now()
                        ),
                        statistics,
                        apiDocuments,
                        failedApis
                );

        String id = storageService.save(combinedDocument);

        return ResponseEntity.ok(
                new GenerateResponse(id, downloadUrl(id), statistics,
                        failedApis)
        );
    }

    // Download generated documentation
    @GetMapping("/{documentId}/download")
    public ResponseEntity<ByteArrayResource> download(
            @PathVariable String documentId) {

        byte[] content = storageService.read(documentId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"documentation-"
                                + documentId + ".json\""
                )
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    private DocumentationDocument buildDocumentation(
            SpecificationSource source,
            boolean withConventions) {

        return builderService.build(
                source,
                parserService.parse(source.content()),
                withConventions
        );
    }

    private String downloadUrl(String id) {
        return BASE_PATH + id + "/download";
    }

    private byte[] getFileBytes(MultipartFile file) {

        try {

            return file.getBytes();

        } catch (IOException exception) {

            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to read uploaded file",
                    exception
            );
        }
    }
}
