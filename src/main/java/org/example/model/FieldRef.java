package org.example.model;

import org.example.util.ClassNameValidator;

/**
 * Represents a single field access: ClassName.fieldName.
 * className stores the fully-qualified class name (e.g. "com.example.model.OrderDO").
 * className may be null if the class cannot be resolved.
 */
public record FieldRef(String className, String fieldName) {

    /**
     * Returns a display-friendly string using the simple class name.
     * Example: "com.example.model.OrderDO.orderId" → "OrderDO.orderId"
     */
    @Override
    public String toString() {
        String simple = ClassNameValidator.extractSimpleName(className);
        return simple != null ? simple + "." + fieldName : "?." + fieldName;
    }
}
