package org.example.model;

import java.util.List;

/**
 * Represents one field mapping relationship found in source code.
 * Covers both read (equals-based) and write (assignment-based) associations.
 */
public record FieldMapping(
        ExpressionSide leftSide,    // data source side
        ExpressionSide rightSide,   // data sink side
        MappingType type,
        MappingMode mode,
        String rawExpression,
        String location,            // "FileName.java:42"
        List<String> normalization  // GAP-03: Normalization operations like ["toLowerCase", "trim"]
) {
    // Backward-compatible constructor
    public FieldMapping(ExpressionSide leftSide, ExpressionSide rightSide, MappingType type,
                       MappingMode mode, String rawExpression, String location) {
        this(leftSide, rightSide, type, mode, rawExpression, location, List.of());
    }
}
