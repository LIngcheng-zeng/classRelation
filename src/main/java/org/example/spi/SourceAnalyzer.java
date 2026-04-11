package org.example.spi;

import org.example.model.FieldMapping;
import org.example.resolution.SymbolResolutionResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Extension point for plugging in different AST analysis backends.
 *
 * Each implementation receives a pre-built {@link SymbolResolutionResult} so that
 * symbol data (type maps, class-package index, Spoon model) is available immediately,
 * with no implicit ordering dependency between analyzers.
 *
 * Adding a new backend requires only:
 *   1. Implementing this interface
 *   2. Registering it in {@code LineageAnalyzer}'s constructor
 */
public interface SourceAnalyzer {

    /**
     * Extracts raw field mappings from {@code projectRoot}.
     *
     * @param projectRoot root directory of the project to analyze
     * @param symbols     pre-built symbol resolution artifacts; never null
     * @return discovered field mappings; never null, may be empty
     */
    List<FieldMapping> analyze(Path projectRoot, SymbolResolutionResult symbols);
}
