package org.example.model;

import java.util.List;

/**
 * Aggregates all field mappings between a source class and a target class.
 */
public record ClassRelation(
        String sourceClass,
        String targetClass,
        List<FieldMapping> mappings,
        InheritanceInfo inheritance  // Optional: inheritance relationship
) {
    /**
     * Represents inheritance information between classes.
     */
    public record InheritanceInfo(
            String childClass,      // e.g., "VipUser"
            String parentClass,     // e.g., "User"
            List<String> inheritedFields  // Fields inherited from parent
    ) {}
    
    // Backward-compatible constructor
    public ClassRelation(String sourceClass, String targetClass, List<FieldMapping> mappings) {
        this(sourceClass, targetClass, mappings, null);
    }
}
