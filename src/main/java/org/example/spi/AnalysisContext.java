package org.example.spi;

import org.example.analyzer.spoon.FieldTypeMap;

/**
 * Shared mutable state passed between {@link SourceAnalyzer} instances
 * during a single analysis run.
 *
 * Execution order contract (enforced by {@link org.example.analyzer.LineageAnalyzer}):
 *   1. SpoonAnalyzer runs first  — builds and populates {@link #fieldTypeMap}
 *   2. JavaParserAnalyzer runs second — consumes {@link #fieldTypeMap}
 */
public class AnalysisContext {

    /** Type map built from Spoon CtModel; null until SpoonAnalyzer completes. */
    public volatile FieldTypeMap fieldTypeMap;
}
