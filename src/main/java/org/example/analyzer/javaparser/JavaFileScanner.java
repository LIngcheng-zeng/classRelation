package org.example.analyzer.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.example.spi.AnalysisContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Recursively scans a directory for .java source files.
 * Also builds a map of simple class name → fully qualified name for package filtering.
 */
class JavaFileScanner {

    private static final Logger log = LoggerFactory.getLogger(JavaFileScanner.class);

    /**
     * Scans for Java files and builds class-package mapping.
     *
     * @param rootPath root directory to scan
     * @param ctx analysis context to store the class-package map
     * @return list of Java file paths
     */
    List<Path> scan(Path rootPath, AnalysisContext ctx) {
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Root path must be a directory: " + rootPath);
        }

        List<Path> javaFiles = new ArrayList<>();
        Map<String, String> classPackageMap = new HashMap<>();
        
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                        
                        // Extract package and class name from the file
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
        
        // Store in context for later use
        ctx.classPackageMap = Collections.unmodifiableMap(classPackageMap);
        
        return javaFiles;
    }
    
    /**
     * Extracts package declaration and class names from a Java file.
     */
    private void extractClassInfo(Path javaFile, Map<String, String> classPackageMap) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(javaFile);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                
                // Get package name
                String packageName = cu.getPackageDeclaration()
                        .map(pkg -> pkg.getNameAsString())
                        .orElse("");
                
                // Get all top-level type declarations
                cu.getTypes().forEach(type -> {
                    String className = type.getNameAsString();
                    String qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
                    classPackageMap.put(className, qualifiedName);
                });
            }
        } catch (Exception e) {
            log.debug("Failed to parse {}: {}", javaFile, e.getMessage());
        }
    }
}
