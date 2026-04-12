package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.reference.CtExecutableReference;

import java.util.Optional;

/**
 * Shared utility: resolves the field provenance of a Spoon expression.
 *
 * Handles two forms:
 *   1. Getter call:        obj.getField()   → FieldProvenance(ObjClass, field)
 *   2. Variable read:      someVar          → looks up ProvenanceContext.varProvenance
 *   3. Method reference:   A::getField      → FieldProvenance(A, field)
 *
 * All methods are static and stateless — no instances needed.
 */
final class ProvenanceResolver {

    private ProvenanceResolver() {}

    /**
     * Resolves provenance from an expression, consulting the ProvenanceContext for variables.
     */
    static Optional<FieldProvenance> resolve(CtExpression<?> expr, ProvenanceContext ctx) {
        if (expr instanceof CtInvocation<?> inv && isGetter(inv.getExecutable().getSimpleName())) {
            return fromGetter(inv);
        }
        if (expr instanceof CtVariableRead<?> vr) {
            return ctx.provenanceOf(vr.getVariable().getSimpleName());
        }
        return Optional.empty();
    }

    /**
     * Resolves provenance from a method reference like {@code A::getField}.
     */
    static Optional<FieldProvenance> fromMethodRef(CtExpression<?> expr) {
        if (!(expr instanceof CtExecutableReferenceExpression<?, ?> refExpr)) return Optional.empty();
        CtExecutableReference<?> execRef = refExpr.getExecutable();
        String methodName = execRef.getSimpleName();
        if (!isGetter(methodName)) return Optional.empty();
        String fieldName = getterToField(methodName);
        String className = null;
        try {
            if (execRef.getDeclaringType() != null)
                className = execRef.getDeclaringType().getSimpleName();
        } catch (Exception ignored) {}
        if (className == null) return Optional.empty();
        return Optional.of(FieldProvenance.of(className, fieldName));
    }

    /**
     * Resolves provenance from a getter invocation like {@code obj.getField()}.
     * Class name comes from Spoon type resolution of the target; falls back to
     * capitalizing the target's simple text representation.
     */
    static Optional<FieldProvenance> fromGetter(CtInvocation<?> inv) {
        String methodName = inv.getExecutable().getSimpleName();
        if (!isGetter(methodName)) return Optional.empty();
        String fieldName = getterToField(methodName);
        String className = resolveTargetClass(inv);
        if (className == null) return Optional.empty();
        return Optional.of(FieldProvenance.of(className, fieldName));
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static String resolveTargetClass(CtInvocation<?> inv) {
        if (inv.getTarget() == null) return null;

        // Primary: Spoon type system
        try {
            var type = inv.getTarget().getType();
            if (type != null) return type.getSimpleName();
        } catch (Exception ignored) {}

        // Fallback: capitalize the target's text (e.g. "user" → "User")
        String text = inv.getTarget().toString();
        if (text.isEmpty()) return null;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static boolean isGetter(String name) {
        return name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3));
    }

    static String getterToField(String getterName) {
        return Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4);
    }
}
