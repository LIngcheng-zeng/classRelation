package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtField;
import spoon.reflect.reference.CtTypeReference;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a Map expression to a {@link MapFact}, looking beyond the local
 * {@link ProvenanceContext} when necessary.
 *
 * Resolution priority (the first match wins):
 *
 *   1. CtFieldRead — checked BEFORE the generic CtVariableRead path, because
 *      CtFieldRead IS-A CtVariableRead in Spoon's hierarchy.
 *      1a. Static field  (target is CtTypeAccess, or declaration is static)
 *          → registry.lookupStatic(className, fieldName)
 *      1b. Instance field
 *          → registry.lookupField(className, fieldName)
 *
 *   2. CtVariableRead (parameters and unresolved locals)
 *      2a. Local context hit
 *          → ctx.mapFact(varName)
 *      2b. Map-typed variable not in context (parameter inference fallback)
 *          → synthetic MapFact with FieldProvenance(keyType, "#inferred")
 *             and FieldProvenance(valueType, "#inferred")
 *
 *   3. CtInvocation (getter chain: service.getOrderMap())
 *      → registry.lookupMethodReturn(className, methodName)
 *
 * All resolution is defensive — exceptions are swallowed and return empty.
 */
public final class CrossFileMapResolver {

    private final GlobalMapRegistry registry;

    public CrossFileMapResolver(GlobalMapRegistry registry) {
        this.registry = registry;
    }

    /**
     * Unified resolution entry point for all {@link BridgeDetector} implementations.
     *
     * @param target the expression that is the Map being accessed (e.g. {@code map} in {@code map.get(k)})
     * @param ctx    the current provenance context (local facts checked first)
     * @return the resolved MapFact, or empty if unresolvable
     */
    public Optional<MapFact> resolve(CtExpression<?> target, ProvenanceContext ctx) {
        if (target == null) return Optional.empty();

        // ── 1. CtFieldRead — must be checked before CtVariableRead ───────────
        // CtFieldRead IS-A CtVariableRead in Spoon; catching it here first avoids
        // misrouting instance/static field reads into the parameter-inference branch.
        if (target instanceof CtFieldRead<?> fr) {
            if (isStaticField(fr)) return resolveStaticField(fr);
            return resolveInstanceField(fr);
        }

        // ── 2. CtVariableRead (local variables and method parameters) ─────────
        if (target instanceof CtVariableRead<?> vr) {
            // 2a. Local context — covers all MapFacts registered by Phase 1
            Optional<MapFact> local = ctx.mapFact(vr.getVariable().getSimpleName());
            if (local.isPresent()) return local;
            // 2b. Map-typed variable with no local fact → parameter inference
            return resolveFromType(vr);
        }

        // ── 3. Getter invocation: service.getOrderMap() ──────────────────────
        if (target instanceof CtInvocation<?> inv) {
            return resolveGetterInvocation(inv);
        }

        return Optional.empty();
    }

    // ── scenario 1a: static field ─────────────────────────────────────────────

    private Optional<MapFact> resolveStaticField(CtFieldRead<?> fr) {
        try {
            CtTypeReference<?> declaringType = fr.getVariable().getDeclaringType();
            if (declaringType == null) return Optional.empty();
            String className = classKey(declaringType);
            String fieldName = fr.getVariable().getSimpleName();
            return registry.lookupStatic(className, fieldName);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── scenario 1b: instance field ──────────────────────────────────────────

    private Optional<MapFact> resolveInstanceField(CtFieldRead<?> fr) {
        try {
            CtTypeReference<?> declaringType = fr.getVariable().getDeclaringType();
            if (declaringType == null) return Optional.empty();
            String className = classKey(declaringType);
            String fieldName = fr.getVariable().getQualifiedName();
            return registry.lookupField(className, fieldName);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── scenario 2b: type-based inference (parameter / unresolved variable) ──

    /**
     * Falls back to generic type arguments when no concrete MapFact is in context.
     * Does NOT require the declaration to be resolvable — uses the variable's
     * declared type reference directly, which Spoon retains in all parse modes.
     *
     * Produces FieldProvenance with {@code "#inferred"} sentinel fields so that
     * downstream consumers can distinguish these mappings from fully-resolved ones.
     */
    private Optional<MapFact> resolveFromType(CtVariableRead<?> vr) {
        try {
            CtTypeReference<?> varType = vr.getType();
            if (varType == null || !isMapType(varType)) return Optional.empty();

            List<CtTypeReference<?>> args = varType.getActualTypeArguments();
            if (args == null || args.size() < 2) return Optional.empty();

            String keyClass   = args.get(0).getSimpleName();
            String valueClass = args.get(1).getSimpleName();
            if (keyClass == null || keyClass.isBlank()) return Optional.empty();
            if (valueClass == null || valueClass.isBlank()) return Optional.empty();

            String varName = vr.getVariable().getSimpleName();
            return Optional.of(MapFact.of(
                    varName,
                    FieldProvenance.of(keyClass,   "#inferred"),
                    FieldProvenance.of(valueClass, "#inferred"),
                    "inferred from variable type Map<" + keyClass + "," + valueClass + ">"
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── scenario 3: getter invocation ────────────────────────────────────────

    private Optional<MapFact> resolveGetterInvocation(CtInvocation<?> inv) {
        try {
            String methodName = inv.getExecutable().getSimpleName();
            if (!ProvenanceResolver.isGetter(methodName)) return Optional.empty();

            // Primary: declaring type from the executable reference
            CtTypeReference<?> declaringType = inv.getExecutable().getDeclaringType();
            if (declaringType != null) {
                Optional<MapFact> hit = registry.lookupMethodReturn(
                        classKey(declaringType), methodName);
                if (hit.isPresent()) return hit;
            }

            // Fallback: resolve via the receiver's static type
            if (inv.getTarget() != null) {
                CtTypeReference<?> receiverType = inv.getTarget().getType();
                if (receiverType != null) {
                    return registry.lookupMethodReturn(classKey(receiverType), methodName);
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Determines whether a field read is a static access.
     * Primary: checks if the variable declaration is a static field.
     * Fallback: checks if the access target is a type reference (e.g. {@code CacheHolder.MAP}).
     */
    private boolean isStaticField(CtFieldRead<?> fr) {
        try {
            if (fr.getVariable().getDeclaration() instanceof CtField<?> f) {
                return f.isStatic();
            }
        } catch (Exception ignored) {}
        // Fallback: static access always has a CtTypeAccess target, not a variable
        try {
            return fr.getTarget() instanceof CtTypeAccess<?>;
        } catch (Exception ignored) {}
        return false;
    }

    private static String classKey(CtTypeReference<?> typeRef) {
        try {
            String qn = typeRef.getQualifiedName();
            return (qn != null && !qn.isBlank()) ? qn : typeRef.getSimpleName();
        } catch (Exception ignored) {
            return typeRef.getSimpleName();
        }
    }

    private boolean isMapType(CtTypeReference<?> typeRef) {
        if (typeRef == null) return false;
        String name = typeRef.getSimpleName();
        return name.equals("Map") || name.equals("HashMap") || name.equals("LinkedHashMap")
                || name.equals("TreeMap") || name.equals("ConcurrentHashMap")
                || name.equals("ConcurrentMap") || name.equals("SortedMap");
    }
}
