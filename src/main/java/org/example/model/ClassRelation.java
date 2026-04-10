package org.example.model;

import java.util.List;

/**
 * Aggregates all field mappings between a source class and a target class.
 */
public record ClassRelation(
        String sourceClass,           // Simple name: "User"
        String targetClass,           // Simple name: "Order"
        String sourceQualifiedClass,  // Fully qualified: "org.example.model.User" (may be null)
        String targetQualifiedClass,  // Fully qualified: "org.example.dto.Order" (may be null)
        List<FieldMapping> mappings,
        InheritanceInfo inheritance   // Optional: inheritance relationship
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
        this(sourceClass, targetClass, null, null, mappings, null);
    }
    
    // Constructor with inheritance info
    public ClassRelation(String sourceClass, String targetClass, List<FieldMapping> mappings, InheritanceInfo inheritance) {
        this(sourceClass, targetClass, null, null, mappings, inheritance);
    }
    
    // Constructor with qualified names
    public ClassRelation(String sourceClass, String targetClass, 
                        String sourceQualifiedClass, String targetQualifiedClass,
                        List<FieldMapping> mappings) {
        this(sourceClass, targetClass, sourceQualifiedClass, targetQualifiedClass, mappings, null);
    }
}
