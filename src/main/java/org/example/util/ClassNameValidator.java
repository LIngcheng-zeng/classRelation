package org.example.util;

/**
 * Utility class for validating and filtering class names.
 * 
 * Centralizes the logic for determining whether a string represents a valid class name,
 * following Strategy C (conservative approach): skip unresolved or noisy class names
 * rather than guessing.
 */
public final class ClassNameValidator {

    private ClassNameValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Checks if a string looks like a valid class name.
     * 
     * Criteria:
     * - Not null or empty
     * - Starts with uppercase letter
     * - Does not contain parentheses (not a method call)
     * - Does not contain dots (not a qualified name or getter chain)
     * 
     * @param name the string to check
     * @return true if it looks like a simple class name
     */
    public static boolean isValidClassName(String name) {
        return name != null
                && !name.isEmpty()
                && Character.isUpperCase(name.charAt(0))
                && !name.contains("(")
                && !name.contains(")");
    }

    /**
     * Checks if a class name is "noisy" (contains artifacts from parsing).
     * 
     * Noisy examples:
     * - "getOrder()" (method call instead of class name)
     * - "orderDTO.getOrder()" (getter chain)
     * - "null" (unresolved)
     * 
     * @param name the class name to check
     * @return true if the name contains noise
     */
    public static boolean isNoisy(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        return name.contains("(") 
            || name.contains(")") 
            || name.equals("null")
            || name.equals("?");
    }

    /**
     * Validates and cleans a class name.
     * Returns null if the name is invalid or noisy (Strategy C).
     * 
     * @param className the raw class name
     * @return cleaned class name, or null if invalid/noisy
     */
    public static String validate(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        
        // Filter out noisy names (Strategy C: don't guess)
        if (isNoisy(className)) {
            return null;
        }
        
        // Must look like a valid class name
        if (!isValidClassName(className)) {
            return null;
        }
        
        return className;
    }

    /**
     * Extracts simple class name from a qualified name.
     * 
     * Example: "com.example.model.User" → "User"
     * 
     * @param qualifiedName the fully qualified class name
     * @return simple class name, or null if input is null
     */
    public static String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }

    /**
     * Capitalizes the first letter of a string (variable name → class name hint).
     * 
     * Example: "order" → "Order", "userDTO" → "UserDTO"
     * 
     * @param variableName the variable name
     * @return capitalized version
     */
    public static String capitalizeFirst(String variableName) {
        if (variableName == null || variableName.isEmpty()) {
            return variableName;
        }
        return Character.toUpperCase(variableName.charAt(0)) + variableName.substring(1);
    }

    /**
     * Checks if a field reference has a valid (non-null, non-noisy) class name.
     * 
     * @param className the class name from a FieldRef
     * @return true if the class name is present and valid
     */
    public static boolean hasValidClass(String className) {
        return validate(className) != null;
    }
}
