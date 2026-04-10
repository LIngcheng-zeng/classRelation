package org.example.analyzer.spoon;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Type index built from a Spoon {@link CtModel}.
 *
 * Provides two lookups:
 *   1. fieldName  → Set&lt;className&gt; — which classes declare this field
 *                   (includes fields inherited via superclass chain)
 *   2. className + fieldName → fieldType simple name — for future use
 *
 * Consumed by {@link org.example.analyzer.javaparser.TypeEnrichingDecorator}
 * to repair syntactically noisy class names in {@link org.example.model.FieldRef}s.
 */
public class FieldTypeMap {

    /** fieldName → set of simple class names that own the field (direct or inherited) */
    private final Map<String, Set<String>> fieldToClasses = new HashMap<>();

    /** className → (fieldName → field type simple name) */
    private final Map<String, Map<String, String>> classFieldTypes = new HashMap<>();

    private FieldTypeMap() {}

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public static FieldTypeMap build(CtModel model) {
        FieldTypeMap map = new FieldTypeMap();
        for (CtType<?> type : model.getAllTypes()) {
            map.indexType(type);
        }
        return map;
    }

    private void indexType(CtType<?> type) {
        String className = type.getSimpleName();
        if (className == null || className.isEmpty()) return;

        Map<String, String> fields = new LinkedHashMap<>();
        collectInheritedFields(type, fields, new HashSet<>());

        classFieldTypes.put(className, fields);
        for (String fieldName : fields.keySet()) {
            fieldToClasses.computeIfAbsent(fieldName, k -> new LinkedHashSet<>()).add(className);
        }
    }

    /**
     * Recursively collects fields from the superclass chain (superclass first,
     * subclass fields override).
     */
    private void collectInheritedFields(CtType<?> type, Map<String, String> fields, Set<String> visited) {
        if (type == null) return;
        String qualName = type.getQualifiedName();
        if (qualName == null || visited.contains(qualName)) return;
        visited.add(qualName);

        // Superclass first so subclass fields take priority on name collision
        var superRef = type.getSuperclass();
        if (superRef != null) {
            try {
                collectInheritedFields(superRef.getTypeDeclaration(), fields, visited);
            } catch (Exception ignored) {}
        }

        for (CtField<?> field : type.getFields()) {
            try {
                String typeName = field.getType().getSimpleName();
                fields.put(field.getSimpleName(), typeName);
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns all class simple names that declare {@code fieldName}
     * (directly or via inheritance).
     */
    public Set<String> getClassesForField(String fieldName) {
        return fieldToClasses.getOrDefault(fieldName, Collections.emptySet());
    }

    /**
     * Returns the declared field type simple name for the given class and field,
     * or {@code null} if unknown.
     */
    public String getFieldType(String className, String fieldName) {
        Map<String, String> fields = classFieldTypes.get(className);
        return fields != null ? fields.get(fieldName) : null;
    }

    @Override
    public String toString() {
        int totalFields = fieldToClasses.size();
        int totalClasses = classFieldTypes.size();
        return "FieldTypeMap{classes=" + totalClasses + ", distinctFields=" + totalFields + "}";
    }
}
