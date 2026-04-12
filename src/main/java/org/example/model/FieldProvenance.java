package org.example.model;

import java.util.List;

/**
 * Tracks the origin of a runtime value back to the source field that produced it.
 *
 * A value may have passed through zero or more transformations (e.g. toLowerCase, trim)
 * before being used; the transform chain records these for normalization analysis.
 *
 * Examples:
 *   enterprise.getName()         → FieldProvenance("Enterprise", "name", [])
 *   user.getCode().toLowerCase() → FieldProvenance("User", "code", ["toLowerCase()"])
 */
public record FieldProvenance(
        String originClass,
        String originField,
        List<String> transformChain
) {

    /** Convenience factory — no transformations. */
    public static FieldProvenance of(String originClass, String originField) {
        return new FieldProvenance(originClass, originField, List.of());
    }

    /**
     * Two provenances are from the same origin if their class and field match,
     * ignoring any intermediate transformations.
     */
    public boolean isSameOrigin(FieldProvenance other) {
        if (other == null) return false;
        return java.util.Objects.equals(originClass, other.originClass)
                && java.util.Objects.equals(originField, other.originField);
    }

    @Override
    public String toString() {
        String base = originClass + "." + originField;
        return transformChain.isEmpty() ? base : base + transformChain;
    }
}
