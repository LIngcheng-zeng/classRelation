package org.example.model;

import org.example.util.ClassNameValidator;

import java.util.List;

/**
 * Aggregates all field mappings between a source class and a target class.
 * sourceClass and targetClass store fully-qualified names (e.g. "com.example.model.OrderDO").
 * Use simpleSourceClass() / simpleTargetClass() when rendering output.
 */
public record ClassRelation(
        String sourceClass,   // Fully qualified: "com.example.model.OrderDO"
        String targetClass,   // Fully qualified: "com.example.dto.InvoiceDO"
        List<FieldMapping> mappings,
        InheritanceInfo inheritance
) {
    /**
     * Represents inheritance information between classes.
     * childClass and parentClass store fully-qualified names.
     */
    public record InheritanceInfo(
            String childClass,           // FQN, e.g. "com.example.model.VipUser"
            String parentClass,          // FQN, e.g. "com.example.model.User"
            List<String> inheritedFields
    ) {
        public String simpleChildClass()  { return ClassNameValidator.extractSimpleName(childClass); }
        public String simpleParentClass() { return ClassNameValidator.extractSimpleName(parentClass); }
    }

    public String simpleSourceClass() { return ClassNameValidator.extractSimpleName(sourceClass); }
    public String simpleTargetClass() { return ClassNameValidator.extractSimpleName(targetClass); }

    // Backward-compatible constructors
    public ClassRelation(String sourceClass, String targetClass, List<FieldMapping> mappings) {
        this(sourceClass, targetClass, mappings, null);
    }
}
