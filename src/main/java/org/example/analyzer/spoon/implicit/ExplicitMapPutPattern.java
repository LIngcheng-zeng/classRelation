package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtField;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 collector: builds MapFacts from explicit {@code map.put(keyExpr, valueExpr)} calls.
 *
 * Recognized form:
 *   Map<K, V> cache = new HashMap<>();
 *   cache.put(a.getX(), b.getY());
 *
 * Also supports static field Maps:
 *   class Holder { static Map<String, String> map = new HashMap<>(); }
 *   Holder.map.put(obj.getKey(), obj.getValue());
 *
 * Only registers a MapFact when all observed put() calls for a given variable
 * agree on the same key/value origin (heterogeneous puts are silently skipped).
 *
 * Bridge detection is handled by {@link DirectGetBridgePattern}.
 */
public class ExplicitMapPutPattern implements MapFactCollector {

    @Override
    public void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx, GlobalMapRegistry globalRegistry) {
        Map<String, FieldProvenance> keySeenMap      = new LinkedHashMap<>();
        Map<String, FieldProvenance> valueSeenMap    = new LinkedHashMap<>();
        Map<String, Boolean>         consistent      = new LinkedHashMap<>();
        // For CtFieldRead targets: tracks declaring class name and static flag for global registration
        Map<String, String>          fieldDeclClass  = new LinkedHashMap<>();
        Map<String, Boolean>         fieldIsStatic   = new LinkedHashMap<>();

        for (CtInvocation<?> inv : scan.invocations) {
            if (!"put".equals(inv.getExecutable().getSimpleName())) continue;
            if (inv.getArguments().size() < 2) continue;

            // Support both CtVariableRead (local vars/params) and CtFieldRead (static/instance fields)
            CtExpression<?> target = inv.getTarget();
            if (target == null) continue;

            String mapVar;
            if (target instanceof CtFieldRead<?> fr) {
                // Static or instance field: use simple field name as key (consistent with lookup)
                mapVar = fr.getVariable().getSimpleName();
                // Record declaring class and static flag for later global registration
                if (!fieldDeclClass.containsKey(mapVar)) {
                    fieldDeclClass.put(mapVar, resolveDeclaringClass(fr));
                    fieldIsStatic.put(mapVar, resolveIsStatic(fr));
                }
            } else if (target instanceof CtVariableRead<?> vr) {
                // Local variable or parameter: e.g., localMap
                mapVar = vr.getVariable().getSimpleName();
            } else {
                continue;
            }
            
            if (Boolean.FALSE.equals(consistent.get(mapVar))) continue;

            Optional<FieldProvenance> keyProv   = ProvenanceResolver.resolve(inv.getArguments().get(0), ctx);
            Optional<FieldProvenance> valueProv = ProvenanceResolver.resolve(inv.getArguments().get(1), ctx);
            
            if (keyProv.isEmpty() || valueProv.isEmpty()) {
                continue;
            }

            if (!keySeenMap.containsKey(mapVar)) {
                keySeenMap.put(mapVar,   keyProv.get());
                valueSeenMap.put(mapVar, valueProv.get());
                consistent.put(mapVar, Boolean.TRUE);
            } else {
                boolean sameKey   = keySeenMap.get(mapVar).isSameOrigin(keyProv.get());
                boolean sameValue = valueSeenMap.get(mapVar).isSameOrigin(valueProv.get());
                if (!sameKey || !sameValue) consistent.put(mapVar, Boolean.FALSE);
            }
        }

        for (Map.Entry<String, Boolean> e : consistent.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            String mapVar = e.getKey();
            MapFact fact = MapFact.of(
                    mapVar,
                    keySeenMap.get(mapVar),
                    valueSeenMap.get(mapVar),
                    "explicit put() on " + mapVar
            );
            ctx.registerMapFact(fact);
            ctx.registerVarProvenance(mapVar + "#key", keySeenMap.get(mapVar));

            // Register to global registry for cross-file resolution
            if (globalRegistry != null) {
                try {
                    String declClass = fieldDeclClass.get(mapVar);
                    if (declClass != null) {
                        // Field-backed map: register under the declaring class with correct namespace
                        boolean isStatic = Boolean.TRUE.equals(fieldIsStatic.get(mapVar));
                        if (isStatic) {
                            globalRegistry.registerStaticFact(declClass, mapVar, fact);
                        } else {
                            globalRegistry.registerFieldFact(declClass, mapVar, fact);
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("[WARN] ExplicitMapPut global register failed: " + ex.getMessage());
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the simple name of the class that declares the field, or null if unresolvable.
     */
    private String resolveDeclaringClass(CtFieldRead<?> fr) {
        try {
            var declType = fr.getVariable().getDeclaringType();
            if (declType != null) return declType.getSimpleName();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns true when the field read refers to a static field.
     * Primary: checks the declaration; fallback: static access always has CtTypeAccess target.
     */
    private boolean resolveIsStatic(CtFieldRead<?> fr) {
        try {
            if (fr.getVariable().getDeclaration() instanceof CtField<?> f) {
                return f.isStatic();
            }
        } catch (Exception ignored) {}
        try {
            return fr.getTarget() instanceof CtTypeAccess<?>;
        } catch (Exception ignored) {}
        return false;
    }
}
