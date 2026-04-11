package org.example.analyzer.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Recursively scans a directory for .java source files.
 *
 * Returns a {@link ScanResult} containing:
 *   - ordered list of discovered .java paths
 *   - classPackageIndex: simpleName → FQN (for later name qualification)
 *
 * Single-pass walk: files and the class-package index are built together.
 */
public class JavaFileScanner {

    private static final Logger log = LoggerFactory.getLogger(JavaFileScanner.class);

    public record ScanResult(List<Path> javaFiles, Map<String, String> classPackageIndex) {}

    /**
     * Scans {@code rootPath} recursively and returns discovered files + class-package map.
     */
    public ScanResult scan(Path rootPath) {
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Root path must be a directory: " + rootPath);
        }

        List<Path> javaFiles = new ArrayList<>();
        Map<String, String> classPackageMap = new LinkedHashMap<>();

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                        extractClassInfo(file, classPackageMap);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to visit file: {} — {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error scanning directory: " + rootPath, e);
        }

        log.info("Found {} .java files under: {}", javaFiles.size(), rootPath);
        log.info("Mapped {} classes to packages", classPackageMap.size());
        return new ScanResult(Collections.unmodifiableList(javaFiles),
                              Collections.unmodifiableMap(classPackageMap));
    }

    // -------------------------------------------------------------------------

    private void extractClassInfo(Path javaFile, Map<String, String> classPackageMap) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(javaFile);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();

                String packageName = cu.getPackageDeclaration()
                        .map(pkg -> pkg.getNameAsString())
                        .orElse("");

                cu.getTypes().forEach(type -> {
                    String className     = type.getNameAsString();
                    String qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
                    classPackageMap.put(className, qualifiedName);
                });
            }
        } catch (Exception e) {
            log.debug("Failed to parse {}: {}", javaFile, e.getMessage());
        }
    }
}
