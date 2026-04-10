package org.example;

import org.example.analyzer.LineageAnalyzer;
import org.example.model.ClassRelation;
import org.example.renderer.MarkdownDocumentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar classRelation.jar <project-root-path>");
            System.exit(1);
        }

        Path projectRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!projectRoot.toFile().isDirectory()) {
            System.err.println("Error: path is not a directory: " + projectRoot);
            System.exit(1);
        }

        String projectName = projectRoot.getFileName().toString();
        log.info("Analyzing project: " + projectRoot);

        try {
            LineageAnalyzer analyzer = new LineageAnalyzer();
            List<ClassRelation> relations = analyzer.analyze(projectRoot);

            if (relations.isEmpty()) {
                System.out.println("No field associations detected in: " + projectName);
                return;
            }

            String content = new MarkdownDocumentRenderer().render(projectName, relations);

            Path outputFile = Paths.get(projectName + ".md");
            Files.writeString(outputFile, content, StandardCharsets.UTF_8);

            System.out.println("Report written to: " + outputFile.toAbsolutePath());
            System.out.println("  " + relations.size() + " class relation(s), "
                    + relations.stream().mapToLong(r -> r.mappings().size()).sum() + " mapping(s) found.");

        } catch (IOException e) {
            System.err.println("Failed to write report: " + e.getMessage());
            log.error("IO error", e);
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            log.error("Analysis failed", e);
            System.exit(2);
        }
    }
}
