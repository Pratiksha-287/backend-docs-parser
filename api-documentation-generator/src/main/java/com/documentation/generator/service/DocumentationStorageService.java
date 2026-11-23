package com.documentation.generator.service;

import com.documentation.generator.exception.ApiException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Service
public class DocumentationStorageService {

    private final ObjectMapper mapper;

    private final Path outputDirectory;

    private final boolean pretty;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private static final Pattern DOCUMENT_ID_PATTERN =
            Pattern.compile("\\d{8}T\\d{9}Z");

    private long lastTimestampMillis;

    public DocumentationStorageService(
            ObjectMapper mapper,
            @Value("${documentation.storage.directory:./generated}")
            String directory,
            @Value("${documentation.output.pretty:false}")
            boolean pretty) {

        this.mapper = mapper;
        this.pretty = pretty;

        this.outputDirectory =
                Paths.get(directory)
                        .toAbsolutePath()
                        .normalize();
    }

    public synchronized String save(Object document) {

        try {

            Files.createDirectories(outputDirectory);

            long timestampMillis = Math.max(
                    System.currentTimeMillis(),
                    lastTimestampMillis + 1
            );
            lastTimestampMillis = timestampMillis;

            String id = TIMESTAMP_FORMAT.format(
                    Instant.ofEpochMilli(timestampMillis)
            );
            Path outputFile = outputDirectory.resolve(id + ".json");

            while (Files.exists(outputFile)) {
                timestampMillis++;
                lastTimestampMillis = timestampMillis;
                id = TIMESTAMP_FORMAT.format(
                        Instant.ofEpochMilli(timestampMillis)
                );
                outputFile = outputDirectory.resolve(id + ".json");
            }

            (pretty
                    ? mapper.writerWithDefaultPrettyPrinter()
                    : mapper.writer())
                    .writeValue(outputFile.toFile(), document);

            return id;

        } catch (IOException exception) {

            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to save documentation JSON",
                    exception
            );
        }
    }

    public byte[] read(String id) {

        if (id == null || !DOCUMENT_ID_PATTERN.matcher(id).matches()) {
            throw new ApiException("Invalid documentation ID");
        }

        // Rebuild the filename from the validated timestamp so that
        // user-controlled text can never reach the file system path.
        Path file = outputDirectory.resolve(id + ".json");

        if (!Files.isRegularFile(file)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Documentation file not found"
            );
        }

        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to read documentation file",
                    exception
            );
        }
    }
}
