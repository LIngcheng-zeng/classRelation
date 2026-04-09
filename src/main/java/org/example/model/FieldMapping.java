package org.example.model;

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
        String location             // "FileName.java:42"
) {}
