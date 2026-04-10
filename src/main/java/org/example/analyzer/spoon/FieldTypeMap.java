package org.example.analyzer.spoon;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

import java.util.*;

/**
 * Type index built from a Spoon {@link CtModel}.
 *
 * All keys use fully-qualified class names (FQN).
 *
 * Provides three lookups:
 *   1. fieldName  → Set&lt;FQN&gt;        — which classes (by FQN) declare this field
 *   2. FQN + fieldName → field type simple name
 *   3. FQN → simple class name      — for display conversion
 *
 * Consumed by {@link org.example.analyzer.javaparser.TypeEnrichingDecorator}.
 */
public class FieldTypeMap {

    /** fieldName → set of FQN class names that own the field (direct or inherited) */
    private final Map<String, Set<String>> fieldToClasses = new HashMap<>();

    /** FQN → (fieldName → field type simple name) */
    private final Map<String, Map<String, String>> classFieldTypes = new HashMap<>();

    /** FQN → simple class name (reverse lookup for display) */
    private final Map<String, String> qualifiedToSimple = new HashMap<>();

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
        String qualifiedName = type.getQualifiedName();
        String simpleName    = type.getSimpleName();
        if (qualifiedName == null || qualifiedName.isEmpty()) return;

        qualifiedToSimple.put(qualifiedName, simpleName);

        Map<String, String> fields = new LinkedHashMap<>();
        collectInheritedFields(type, fields, new HashSet<>());

        classFieldTypes.put(qualifiedName, fields);
        for (String fieldName : fields.keySet()) {
            fieldToClasses.computeIfAbsent(fieldName, k -> new LinkedHashSet<>()).add(qualifiedName);
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

        var superRef = type.getSuperclass();
        if (superRef != null) {
            try {
                collectInheritedFields(superRef.getTypeDeclaration(), fields, visited);
            } catch (Exception ignored) {}
        }

        for (CtField<?> field : type.getFields()) {
            try {
                // Prefer getTypeDeclaration() to resolve same-package types to FQN
                String typeFqn;
                try {
                    CtType<?> typeDecl = field.getType().getTypeDeclaration();
                    typeFqn = (typeDecl != null) ? typeDecl.getQualifiedName()
                                                 : field.getType().getQualifiedName();
                } catch (Exception ignored2) {
                    typeFqn = field.getType().getQualifiedName();
                }
                fields.put(field.getSimpleName(), typeFqn);
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns all FQN class names that declare {@code fieldName}
     * (directly or via inheritance).
     */
    public Set<String> getClassesForField(String fieldName) {
        return fieldToClasses.getOrDefault(fieldName, Collections.emptySet());
    }

    /**
     * Returns the declared field type simple name for the given FQN class and field,
     * or {@code null} if unknown.
     */
    public String getFieldType(String qualifiedName, String fieldName) {
        Map<String, String> fields = classFieldTypes.get(qualifiedName);
        return fields != null ? fields.get(fieldName) : null;
    }

    /**
     * Returns the simple class name for a given FQN, or {@code null} if not indexed.
     */
    public String getSimpleName(String qualifiedName) {
        return qualifiedToSimple.get(qualifiedName);
    }

    @Override
    public String toString() {
        return "FieldTypeMap{classes=" + classFieldTypes.size()
                + ", distinctFields=" + fieldToClasses.size() + "}";
    }
}
