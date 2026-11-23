
package org.example.sourcecodeanalyser.service;

import org.example.sourcecodeanalyser.extractor.CSSSourceExtractor;
import org.example.sourcecodeanalyser.extractor.SCSSSourceExtractor;

import org.example.sourcecodeanalyser.model.CSSAnalysisResult;
import org.example.sourcecodeanalyser.model.SCSSAnalysisResult;
import org.example.sourcecodeanalyser.model.FileDiscoveryResult;
import org.example.sourcecodeanalyser.model.JavaAnalysisResult;
import org.example.sourcecodeanalyser.model.RepositoryAnalysisResult;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RepositoryAnalysisService {

    private final RepositoryScanService repositoryScanService;
    private final JavaAnalysisService javaAnalysisService;
    private final CSSSourceExtractor cssSourceExtractor;
    private final SCSSSourceExtractor scssSourceExtractor;

    public RepositoryAnalysisService(
            RepositoryScanService repositoryScanService,
            JavaAnalysisService javaAnalysisService,
            CSSSourceExtractor cssSourceExtractor,
            SCSSSourceExtractor scssSourceExtractor) {

        this.repositoryScanService = repositoryScanService;
        this.javaAnalysisService = javaAnalysisService;
        this.cssSourceExtractor = cssSourceExtractor;
        this.scssSourceExtractor = scssSourceExtractor;
    }

    public RepositoryAnalysisResult analyzeRepository(
            Path repositoryPath) throws IOException {

        // Discover repository files.
        FileDiscoveryResult fileDiscovery =
                repositoryScanService.scanRepository(repositoryPath);

        // Analyze Java files.
        List<Path> javaFiles =
                fileDiscovery.getCategorizedFiles()
                        .getOrDefault("JAVA", List.of());

        JavaAnalysisResult javaAnalysis =
                javaAnalysisService.analyzeJavaFiles(javaFiles);

        // Analyze CSS files.
        List<Path> cssFiles =
                fileDiscovery.getCategorizedFiles()
                        .getOrDefault("CSS", List.of());

        List<CSSAnalysisResult> cssAnalysis = new ArrayList<>();

        for (Path cssFile : cssFiles) {
            cssAnalysis.add(cssSourceExtractor.extract(cssFile));
        }

        // Analyze SCSS files.
        List<Path> stylesheetFiles =
                fileDiscovery.getCategorizedFiles()
                        .getOrDefault("STYLESHEETS", List.of());

        List<SCSSAnalysisResult> scssAnalysis = new ArrayList<>();

        for (Path stylesheetFile : stylesheetFiles) {

            String fileName = stylesheetFile
                    .getFileName()
                    .toString()
                    .toLowerCase(Locale.ROOT);

            if (fileName.endsWith(".scss")) {
                scssAnalysis.add(
                        scssSourceExtractor.extract(stylesheetFile)
                );
            }
        }

        // Return combined analysis.
        return new RepositoryAnalysisResult(
                fileDiscovery,
                javaAnalysis,
                cssAnalysis,
                scssAnalysis
        );
    }
}
