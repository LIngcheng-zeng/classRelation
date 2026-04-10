package org.example.spi;

import org.example.model.FieldMapping;

import java.nio.file.Path;
import java.util.List;

/**
 * Extension point for plugging in different AST analysis backends.
 *
 * Each implementation is responsible for:
 *   1. Scanning / parsing the project source files in its own way
 *   2. Producing a flat list of {@link FieldMapping}s
 *
 * The orchestrator ({@code LineageAnalyzer}) aggregates results from all registered
 * implementations — adding a new backend (e.g. Spoon) requires only:
 *   1. Creating a class that implements this interface
 *   2. Registering it in {@code LineageAnalyzer}'s constructor
 */
public interface SourceAnalyzer {

    /**
     * Analyzes all source files under {@code projectRoot} and returns the
     * raw field mappings discovered, without transitive expansion.
     *
     * @param projectRoot root directory of the project to analyze
     * @return discovered field mappings; never null, may be empty
     */
    List<FieldMapping> analyze(Path projectRoot);
}
