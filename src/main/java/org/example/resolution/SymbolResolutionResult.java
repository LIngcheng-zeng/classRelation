package org.example.resolution;

import org.example.analyzer.spoon.FieldTypeMap;
import org.example.model.ClassRelation;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all symbol-resolution artifacts produced before extraction begins.
 *
 * Replaces the mutable, temporally-coupled {@code AnalysisContext} bag.
 * Built once by {@link SymbolResolver}; passed read-only to every {@code SourceAnalyzer}.
 *
 * Fields:
 *   fieldTypeIndex      — FQN → declared fields + field-type FQN; also FQN ↔ simpleName
 *   classPackageIndex   — simpleName → FQN (for qualifying unresolved simple names)
 *   inheritanceIndex    — child FQN → InheritanceInfo
 *   spoonModel          — Spoon CtModel for extractors that need AST access (may be null on failure)
 *   javaFiles           — ordered list of .java paths under projectRoot (avoids re-scanning)
 */
public record SymbolResolutionResult(
        FieldTypeMap fieldTypeIndex,
        Map<String, String> classPackageIndex,
        Map<String, ClassRelation.InheritanceInfo> inheritanceIndex,
        CtModel spoonModel,
        List<Path> javaFiles
) {

    /** Fallback used when symbol resolution fails completely. */
    public static SymbolResolutionResult empty() {
        return new SymbolResolutionResult(null, Map.of(), Map.of(), null, List.of());
    }
}
