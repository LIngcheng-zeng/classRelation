package org.example;

import org.example.analyzer.LineageAnalyzer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Parse command-line arguments
        Path projectRoot = null;
        List<String> targetPackages = new ArrayList<>();
        boolean includeTransitive = true; // Default: include relations where at least one side matches

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--package") || args[i].equals("-p")) {
                if (i + 1 < args.length) {
                    targetPackages.add(args[++i]);
                } else {
                    System.err.println("Error: --package requires a value");
                    System.exit(1);
                }
            } else if (args[i].equals("--strict")) {
                includeTransitive = false; // Only include relations where BOTH sides match
            } else if (projectRoot == null) {
                projectRoot = Paths.get(args[i]).toAbsolutePath().normalize();
            } else {
                System.err.println("Error: unexpected argument: " + args[i]);
                System.exit(1);
            }
        }

        if (projectRoot == null) {
            System.err.println("Usage: java -jar classRelation.jar <project-root-path> [--package <pkg>] [--strict]");
            System.err.println("  --package, -p <pkg>: Filter to show only relations involving specified package(s)");
            System.err.println("                     Supports: com.example, com.example.*, com.example.**");
            System.err.println("  --strict: Only include relations where BOTH source and target are in target packages");
            System.exit(1);
        }

        if (!projectRoot.toFile().isDirectory()) {
            System.err.println("Error: path is not a directory: " + projectRoot);
            System.exit(1);
        }

        String projectName = projectRoot.getFileName().toString();
        log.info("Analyzing project: " + projectRoot);
        if (!targetPackages.isEmpty()) {
            log.info("Package filter: {} (mode: {})", 
                    targetPackages, includeTransitive ? "transitive" : "strict");
        }

        try {
            LineageAnalyzer analyzer = new LineageAnalyzer();
            List<ClassRelation> allRelations = analyzer.analyze(projectRoot);

            // Apply package filter if specified
            List<ClassRelation> filteredRelations = allRelations;
            if (!targetPackages.isEmpty()) {
                PackageFilter filter = new PackageFilter(targetPackages);
                
                // Get class-package map from the analyzer's context
                // We need to access it through a different approach since LineageAnalyzer doesn't expose it
                // For now, we'll build it on-the-fly by scanning the project again
                Map<String, String> classPackageMap = buildClassPackageMap(projectRoot);
                
                filteredRelations = allRelations.stream()
                        .filter(rel -> {
                            // Look up fully qualified names
                            String sourceQualified = classPackageMap.getOrDefault(rel.sourceClass(), rel.sourceClass());
                            String targetQualified = classPackageMap.getOrDefault(rel.targetClass(), rel.targetClass());
                            
                            return filter.shouldIncludeRelation(sourceQualified, targetQualified);
                        })
                        .toList();
                
                log.info("Filtered: {} -> {} relations", allRelations.size(), filteredRelations.size());
            }

            if (filteredRelations.isEmpty()) {
                System.out.println("No field associations detected in: " + projectName);
                return;
            }

            String suffix = !targetPackages.isEmpty() ? "-filtered" : "";

            String content = new MarkdownDocumentRenderer().render(projectName, filteredRelations);
            Path outputFile = Paths.get(projectName + suffix + ".md");
            Files.writeString(outputFile, content, StandardCharsets.UTF_8);

            String htmlContent = new AntVG6HtmlRenderer().render(projectName, filteredRelations);
            Path htmlOutputFile = Paths.get(projectName + suffix + ".html");
            Files.writeString(htmlOutputFile, htmlContent, StandardCharsets.UTF_8);

            System.out.println("Report written to: " + outputFile.toAbsolutePath());
            System.out.println("Graph HTML written to: " + htmlOutputFile.toAbsolutePath());
            System.out.println("  " + filteredRelations.size() + " class relation(s), "
                    + filteredRelations.stream().mapToLong(r -> r.mappings().size()).sum() + " mapping(s) found.");

            // Write to NebulaGraph
            String spaceName = toNebulaSpaceName(projectName + suffix);
            NebulaConfig nebulaConfig = NebulaConfig.defaultLocal(spaceName);
            try (NebulaWriter writer = new NebulaWriter(nebulaConfig)) {
                writer.write(filteredRelations);
                System.out.println("NebulaGraph written to space: " + spaceName);
            } catch (Exception e) {
                log.warn("NebulaGraph write failed (non-fatal): {}", e.getMessage());
                System.err.println("Warning: NebulaGraph write failed: " + e.getMessage());
            }

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
    
    /**
     * Converts a project name to a valid NebulaGraph space name.
     * Rules: start with letter/underscore, only [a-zA-Z0-9_], max 256 chars.
     * Example: "myProject-filtered" → "myProject_filtered"
     */
    private static String toNebulaSpaceName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!sanitized.isEmpty() && Character.isDigit(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return sanitized.length() > 256 ? sanitized.substring(0, 256) : sanitized;
    }

    /**
     * Builds a map of simple class name to fully qualified name by scanning Java files.
     */
    private static Map<String, String> buildClassPackageMap(Path projectRoot) {
        Map<String, String> classPackageMap = new java.util.HashMap<>();
        
        try {
            java.nio.file.Files.walk(projectRoot)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(javaFile -> {
                        try {
                            com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> result = 
                                new com.github.javaparser.JavaParser().parse(javaFile);
                            if (result.isSuccessful() && result.getResult().isPresent()) {
                                com.github.javaparser.ast.CompilationUnit cu = result.getResult().get();
                                
                                String packageName = cu.getPackageDeclaration()
                                        .map(pkg -> pkg.getNameAsString())
                                        .orElse("");
                                
                                cu.getTypes().forEach(type -> {
                                    String className = type.getNameAsString();
                                    String qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
                                    classPackageMap.put(className, qualifiedName);
                                });
                            }
                        } catch (Exception e) {
                            // Ignore parse errors
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to build class-package map: {}", e.getMessage());
        }
        
        return classPackageMap;
    }
}
