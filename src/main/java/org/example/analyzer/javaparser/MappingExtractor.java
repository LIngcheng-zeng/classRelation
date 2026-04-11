package org.example.analyzer.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import org.example.classifier.RelationshipClassifier;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;

import java.util.List;

/**
 * Internal extension point within the JavaParser backend.
 *
 * Each implementation detects one category of field mapping pattern
 * (equals-based, assignment-based, setter-based, etc.) from a parsed AST.
 *
 * To add a new JavaParser-based detection pattern:
 *   1. Implement this interface
 *   2. Register the instance in {@link JavaParserAnalyzer}
 */
public interface MappingExtractor {

    /**
     * Scans {@code cu} and returns all field mappings found by this extractor.
     *
     * @param cu              parsed compilation unit
     * @param fileName        source file name, used for location strings
     * @param fieldRefExtractor shared extractor for resolving field references
     * @param classifier      shared classifier for determining mapping type
     * @return discovered mappings; never null, may be empty
     */
    List<FieldMapping> extract(CompilationUnit cu,
                               String fileName,
                               FieldRefExtractor fieldRefExtractor,
                               RelationshipClassifier classifier);

    // -------------------------------------------------------------------------
    // Shared validation / utilities
    // -------------------------------------------------------------------------

    default boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }

    default String truncate(String expr) {
        return expr.length() > 120 ? expr.substring(0, 117) + "..." : expr;
    }
}
