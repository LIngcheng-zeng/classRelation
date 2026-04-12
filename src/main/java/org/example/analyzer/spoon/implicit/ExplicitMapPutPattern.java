package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;

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
 * Only registers a MapFact when all observed put() calls for a given variable
 * agree on the same key/value origin (heterogeneous puts are silently skipped).
 *
 * Bridge detection is handled by {@link DirectGetBridgePattern}.
 */
public class ExplicitMapPutPattern implements MapFactCollector {

    @Override
    public void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx) {
        Map<String, FieldProvenance> keySeenMap   = new LinkedHashMap<>();
        Map<String, FieldProvenance> valueSeenMap = new LinkedHashMap<>();
        Map<String, Boolean>         consistent   = new LinkedHashMap<>();

        for (CtInvocation<?> inv : scan.invocations) {
            if (!"put".equals(inv.getExecutable().getSimpleName())) continue;
            if (inv.getArguments().size() < 2) continue;
            if (!(inv.getTarget() instanceof CtVariableRead<?> vr)) continue;

            String mapVar = vr.getVariable().getSimpleName();
            if (Boolean.FALSE.equals(consistent.get(mapVar))) continue;

            Optional<FieldProvenance> keyProv   = ProvenanceResolver.resolve(inv.getArguments().get(0), ctx);
            Optional<FieldProvenance> valueProv = ProvenanceResolver.resolve(inv.getArguments().get(1), ctx);
            if (keyProv.isEmpty() || valueProv.isEmpty()) continue;

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
        }
    }
}
