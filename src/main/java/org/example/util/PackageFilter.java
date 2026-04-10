package org.example.util;

import java.nio.file.Path;
import java.util.List;

/**
 * Filters Java files and class relations based on package names.
 * 
 * Supports:
 * - Exact package match: "com.example.model"
 * - Wildcard match: "com.example.*" (matches com.example.model, com.example.dto, etc.)
 * - Sub-package match: "com.example.**" (matches com.example.model and all sub-packages)
 * 
 * Usage scenarios:
 * 1. File-level filtering: Only analyze Java files in target packages
 * 2. Relation-level filtering: Only output relations involving target packages
 */
public class PackageFilter {

    private final List<String> targetPackages;

    /**
     * Creates a package filter.
     *
     * @param targetPackages list of package patterns to include
     */
    public PackageFilter(List<String> targetPackages) {
        this.targetPackages = targetPackages;
    }

    /**
     * Checks if a class name belongs to any of the target packages.
     *
     * @param fullyQualifiedClassName e.g., "com.example.model.User"
     * @return true if the class is in a target package
     */
    public boolean matchesClass(String fullyQualifiedClassName) {
        if (fullyQualifiedClassName == null || fullyQualifiedClassName.isEmpty()) {
            return false;
        }

        // Extract package name from fully qualified class name
        int lastDot = fullyQualifiedClassName.lastIndexOf('.');
        if (lastDot < 0) {
            // No package (default package)
            return targetPackages.isEmpty();
        }
        String packageName = fullyQualifiedClassName.substring(0, lastDot);

        for (String pattern : targetPackages) {
            if (matchesPattern(packageName, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a relation should be included based on package filtering.
     * Includes if at least one side matches (captures cross-package data flow).
     *
     * @param sourceClass fully qualified or simple source class name
     * @param targetClass fully qualified or simple target class name
     * @return true if the relation should be included
     */
    public boolean shouldIncludeRelation(String sourceClass, String targetClass) {
        // If we only have simple class names (no dots), we can't filter by package
        // In this case, include all relations (filtering should happen at file level)
        if (!sourceClass.contains(".") && !targetClass.contains(".")) {
            return true;
        }
        
        return matchesClass(sourceClass) || matchesClass(targetClass);
    }

    /**
     * Matches a package name against a pattern.
     *
     * Patterns:
     * - "com.example" → exact match
     * - "com.example.*" → matches com.example.model, com.example.dto (one level)
     * - "com.example.**" → matches com.example.model and all sub-packages
     */
    private boolean matchesPattern(String packageName, String pattern) {
        if (pattern.endsWith(".**")) {
            // Sub-package match: "com.example.**" matches "com.example.model.user"
            String prefix = pattern.substring(0, pattern.length() - 3);
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        } else if (pattern.endsWith(".*")) {
            // One-level wildcard: "com.example.*" matches "com.example.model"
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (!packageName.startsWith(prefix + ".")) {
                return false;
            }
            // Ensure only one level deeper
            String remainder = packageName.substring(prefix.length() + 1);
            return !remainder.contains(".");
        } else {
            // Exact match
            return packageName.equals(pattern);
        }
    }
}
