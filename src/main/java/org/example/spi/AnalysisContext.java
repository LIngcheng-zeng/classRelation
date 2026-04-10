package org.example.spi;

import org.example.analyzer.spoon.FieldTypeMap;
import org.example.model.ClassRelation;

import java.util.Map;

/**
 * Shared mutable state passed between {@link SourceAnalyzer} instances
 * during a single analysis run.
 *
 * Execution order contract (enforced by {@link org.example.analyzer.LineageAnalyzer}):
 *   1. SpoonAnalyzer runs first  — builds and populates {@link #fieldTypeMap}, {@link #inheritanceMap}, and {@link #classPackageMap}
 *   2. JavaParserAnalyzer runs second — consumes {@link #fieldTypeMap}
 */
public class AnalysisContext {

    /** Type map built from Spoon CtModel; null until SpoonAnalyzer completes. */
    public volatile FieldTypeMap fieldTypeMap;
    
    /** Inheritance relationships detected by SpoonAnalyzer; null until SpoonAnalyzer completes. */
    public volatile Map<String, ClassRelation.InheritanceInfo> inheritanceMap;
    
    /** Maps simple class name to fully qualified name (including package); built by JavaFileScanner. */
    public volatile Map<String, String> classPackageMap;
}
