
package com.gupta.liferayanalyzer.generator;

import com.gupta.liferayanalyzer.normalizer.NormalizedDocumentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

@Service
public class DocumentationGenerator {

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS")
                    .withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;
    private final boolean prettyPrint;
    private final boolean writeGzip;

    public DocumentationGenerator(
            ObjectMapper objectMapper,
            @Value("${documentation.storage.directory:./generated}")
            String storageDirectory,
            @Value("${documentation.output.pretty-print:false}")
            boolean prettyPrint,
            @Value("${documentation.output.gzip:false}")
            boolean writeGzip) {

        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory)
                .toAbsolutePath()
                .normalize();
        this.prettyPrint = prettyPrint;
        this.writeGzip = writeGzip;
    }

    public Path generate(NormalizedDocumentation documentation) {

        try {
            // Create the output directory if it does not exist.
            Files.createDirectories(storageDirectory);

            String timestamp = FILE_TIMESTAMP_FORMATTER.format(Instant.now());
            Path outputFile = storageDirectory.resolve(
                    "liferay-documentation-" + timestamp + ".json"
            );

            // Write to a temporary file first.
            Path temporaryFile = Files.createTempFile(
                    storageDirectory,
                    outputFile.getFileName().toString(),
                    ".tmp"
            );

            // Compact JSON by default. Indentation alone adds a lot of
            // size on deeply nested documents.
            ObjectWriter writer = prettyPrint
                    ? objectMapper.writerWithDefaultPrettyPrinter()
                    : objectMapper.writer();

            try (OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(temporaryFile), 1 << 16)) {
                writer.writeValue(out, documentation);
            }

            moveIntoPlace(temporaryFile, outputFile);

            if (writeGzip) {
                writeGzipCopy(outputFile);
            }

            return outputFile;

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to generate Liferay documentation JSON",
                    ex
            );
        }
    }

    /** Writes a gzip copy next to the timestamped JSON file when enabled. */
    private void writeGzipCopy(Path jsonFile) throws IOException {

        Path gzipFile = jsonFile.resolveSibling(
                jsonFile.getFileName() + ".gz"
        );

        Path temporaryGzip = Files.createTempFile(
                jsonFile.getParent(),
                jsonFile.getFileName().toString(),
                ".gz.tmp"
        );

        try (OutputStream out = new GZIPOutputStream(
                new BufferedOutputStream(
                        Files.newOutputStream(temporaryGzip), 1 << 16))) {
            Files.copy(jsonFile, out);
        }

        moveIntoPlace(temporaryGzip, gzipFile);
    }

    private void moveIntoPlace(Path source, Path target)
            throws IOException {

        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
