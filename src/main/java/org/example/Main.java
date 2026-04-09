package org.example;

import org.example.analyzer.LineageAnalyzer;
import org.example.model.ClassRelation;
import org.example.renderer.MermaidRenderer;
import org.example.renderer.TableRenderer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar classRelation.jar <project-root-path>");
            System.exit(1);
        }

        Path projectRoot = Paths.get(args[0]);
        if (!projectRoot.toFile().isDirectory()) {
            System.err.println("Error: path is not a directory: " + projectRoot);
            System.exit(1);
        }

        log.info("Analyzing project: " + projectRoot.toAbsolutePath());

        try {
            LineageAnalyzer analyzer = new LineageAnalyzer();
            List<ClassRelation> relations = analyzer.analyze(projectRoot);

            if (relations.isEmpty()) {
                System.out.println("No inter-class equals() relationships detected.");
                return;
            }

            System.out.println("=".repeat(60));
            System.out.println("  Mermaid Diagram");
            System.out.println("=".repeat(60));
            System.out.println(new MermaidRenderer().render(relations));

            System.out.println();
            System.out.println("=".repeat(60));
            System.out.println("  Lineage Table");
            System.out.println("=".repeat(60));
            System.out.println(new TableRenderer().render(relations));

        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            log.severe("Analysis failed: " + e);
            System.exit(2);
        }
    }
}
