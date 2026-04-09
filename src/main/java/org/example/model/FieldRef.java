package org.example.model;

/**
 * Represents a single field access: ClassName.fieldName.
 * className may be null if the class cannot be resolved.
 */
public record FieldRef(String className, String fieldName) {

    @Override
    public String toString() {
        return className != null ? className + "." + fieldName : "?." + fieldName;
    }
}
