package org.example.model;

/**
 * Represents one equals()-based field mapping relationship found in source code.
 */
public record FieldMapping(
        ExpressionSide leftSide,
        ExpressionSide rightSide,
        MappingType type,
        String rawExpression,
        String location          // "FileName.java:42"
) {}
