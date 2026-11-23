
package com.gupta.liferayanalyzer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/analyzer")
public class AnalyzerController {

    @GetMapping("/health")
    public Map<String, String> health() {

        return Map.of(
                "application", "Liferay API Analyzer",
                "status", "RUNNING",
                "message", "Application is ready"
        );
    }
}