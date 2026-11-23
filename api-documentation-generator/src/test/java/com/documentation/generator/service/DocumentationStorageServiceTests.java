package com.documentation.generator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationStorageServiceTests {

    @TempDir
    Path outputDirectory;

    @Test
    void savesFilesWithDescendingTimestampOrderIds() throws Exception {
        DocumentationStorageService storage =
                new DocumentationStorageService(
                        new ObjectMapper(),
                        outputDirectory.toString(),
                        false
                );

        String firstId = storage.save(new TestDocument("first"));
        String secondId = storage.save(new TestDocument("second"));

        assertThat(firstId).matches("\\d{8}T\\d{9}Z");
        assertThat(secondId).matches("\\d{8}T\\d{9}Z");
        assertThat(secondId).isGreaterThan(firstId);

        List<Path> files;
        try (var stream = Files.list(outputDirectory)) {
            files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) ->
                            right.getFileName().toString()
                                    .compareTo(left.getFileName().toString()))
                    .toList();
        }

        assertThat(files).hasSize(2);
        assertThat(files.get(0).getFileName().toString())
                .isEqualTo(secondId + ".json");
    }

    private record TestDocument(String value) {
    }
}
