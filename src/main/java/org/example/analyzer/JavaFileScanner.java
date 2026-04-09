package org.example.analyzer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Recursively scans a directory for .java source files.
 */
public class JavaFileScanner {

    private static final Logger log = Logger.getLogger(JavaFileScanner.class.getName());

    public List<Path> scan(Path rootPath) {
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Root path must be a directory: " + rootPath);
        }

        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warning("Failed to visit file: " + file + " — " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error scanning directory: " + rootPath, e);
        }

        log.info("Found " + javaFiles.size() + " .java files under: " + rootPath);
        return javaFiles;
    }
}
