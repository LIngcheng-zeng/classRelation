package org.example.model;

import java.util.List;

/**
 * Aggregates all field mappings between a source class and a target class.
 */
public record ClassRelation(
        String sourceClass,
        String targetClass,
        List<FieldMapping> mappings
) {}
