package org.example.model;

import java.util.List;

/**
 * One side of an equals() comparison.
 * May contain multiple field references (composite/parameterized cases).
 */
public record ExpressionSide(
        List<FieldRef> fields,
        String operatorDesc   // "direct" | "concat" | "format" | "transform"
) {
    public boolean isEmpty() {
        return fields == null || fields.isEmpty();
    }

    @Override
    public String toString() {
        if (fields == null || fields.isEmpty()) return "<empty>";
        if (fields.size() == 1) return fields.get(0).toString();
        return operatorDesc + "(" + String.join(", ", fields.stream().map(FieldRef::toString).toList()) + ")";
    }
}
