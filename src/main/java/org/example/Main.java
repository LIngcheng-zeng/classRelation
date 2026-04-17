package org.example;

import org.example.analyzer.LineageAnalyzer;
import org.example.config.AppConfig;
import org.example.model.ClassRelation;
import org.example.nebula.NebulaConfig;
import org.example.nebula.NebulaWriter;
import org.example.renderer.AntVG6HtmlRenderer;
import org.example.renderer.MarkdownDocumentRenderer;
import org.example.util.PackageFilter;
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

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.getInstance();

        Path projectRoot = resolveProjectRoot(config);
        String projectName = projectRoot.getFileName().toString();
        Path outputDir = resolveOutputDir(config);

        log.info("Analyzing project: {}", projectRoot);

        List<ClassRelation> relations = analyze(projectRoot, config);

        if (relations.isEmpty()) {
            System.out.println("No field associations detected in: " + projectName);
            return;
        }

        System.out.printf("Analysis complete: %d relation(s), %d mapping(s)%n",
                relations.size(),
                relations.stream().mapToLong(r -> r.mappings().size()).sum());

        writeOutputs(projectName, relations, outputDir, config);
    }

    // -------------------------------------------------------------------------

    private static Path resolveProjectRoot(AppConfig config) {
        String path = config.getProjectPath();
        if (path.isEmpty()) {
            System.err.println("Error: project.path is not configured in classrelation.properties");
            System.exit(1);
        }
        Path root = Paths.get(path).toAbsolutePath().normalize();
        if (!root.toFile().isDirectory()) {
            System.err.println("Error: project.path is not a directory: " + root);
            System.exit(1);
        }
        return root;
    }

    private static Path resolveOutputDir(AppConfig config) throws IOException {
        Path dir = Paths.get(config.getOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    private static List<ClassRelation> analyze(Path projectRoot, AppConfig config) {
        List<ClassRelation> relations = new LineageAnalyzer().analyze(projectRoot);

        if (config.hasPackageFilters()) {
            PackageFilter filter = new PackageFilter(config.getPackageFilters());
            relations = relations.stream()
                    .filter(r -> filter.shouldIncludeRelation(r.sourceClass(), r.targetClass()))
                    .toList();
            log.info("Package filter applied: {} relation(s) retained", relations.size());
        }

        return relations;
    }

    private static void writeOutputs(String projectName, List<ClassRelation> relations,
                                     Path outputDir, AppConfig config) {
        if (config.isMarkdownEnabled()) {
            writeMarkdown(projectName, relations, outputDir);
        }
        if (config.isHtmlEnabled()) {
            writeHtml(projectName, relations, outputDir, config);
        }
        if (config.isNebulaEnabled()) {
            writeNebula(projectName, relations);
        }
    }

    private static void writeMarkdown(String projectName, List<ClassRelation> relations, Path outputDir) {
        try {
            String content = new MarkdownDocumentRenderer().render(projectName, relations);
            Path output = outputDir.resolve(projectName + ".md");
            Files.writeString(output, content, StandardCharsets.UTF_8);
            System.out.println("Markdown written to: " + output);
        } catch (IOException e) {
            log.error("Failed to write Markdown", e);
            System.err.println("Warning: failed to write Markdown: " + e.getMessage());
        }
    }

    private static void writeHtml(String projectName, List<ClassRelation> relations,
                                   Path outputDir, AppConfig config) {
        try {
            String content = new AntVG6HtmlRenderer().render(
                    projectName, relations, config.getMaxComponentsToRender());
            Path output = outputDir.resolve(projectName + ".html");
            Files.writeString(output, content, StandardCharsets.UTF_8);
            System.out.println("HTML written to: " + output);
        } catch (IOException e) {
            log.error("Failed to write HTML", e);
            System.err.println("Warning: failed to write HTML: " + e.getMessage());
        }
    }

    private static void writeNebula(String projectName, List<ClassRelation> relations) {
        String spaceName = toNebulaSpaceName(projectName);
        NebulaConfig nebulaConfig = NebulaConfig.defaultLocal(spaceName);
        try (NebulaWriter writer = new NebulaWriter(nebulaConfig)) {
            writer.write(relations);
            System.out.println("NebulaGraph written to space: " + spaceName);
        } catch (Exception e) {
            log.warn("NebulaGraph write failed (non-fatal): {}", e.getMessage());
            System.err.println("Warning: NebulaGraph write failed: " + e.getMessage());
        }
    }

    private static String toNebulaSpaceName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!sanitized.isEmpty() && Character.isDigit(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return sanitized.length() > 256 ? sanitized.substring(0, 256) : sanitized;
    }
}
