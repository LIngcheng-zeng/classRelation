package org.example.analyzer.spoon.implicit;

import org.example.model.FieldMapping;
import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 pattern: builds MapFacts from explicit {@code map.put(keyExpr, valueExpr)} calls.
 *
 * Recognized form:
 *   Map<K, V> cache = new HashMap<>();
 *   cache.put(a.getX(), b.getY());   // may appear in a loop or branch
 *
 * Strategy:
 *   Scan all put() calls in the method body. For each map variable, collect all
 *   (keyProvenance, valueProvenance) pairs. If all observed pairs agree on the same
 *   key/value origins, register a MapFact. Disagreement (heterogeneous puts) is
 *   silently skipped to avoid false positives.
 *
 * Bridge detection is handled by {@link DirectGetBridgePattern}.
 */
public class ExplicitMapPutPattern implements KeyUsagePattern {

    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {
        // varName → list of (keyProv, valueProv) pairs seen in put() calls
        Map<String, FieldProvenance> keySeenMap   = new LinkedHashMap<>();
        Map<String, FieldProvenance> valueSeenMap = new LinkedHashMap<>();
        Map<String, Boolean>         consistent    = new LinkedHashMap<>();

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"put".equals(inv.getExecutable().getSimpleName())) continue;
            if (inv.getArguments().size() < 2) continue;
            if (!(inv.getTarget() instanceof CtVariableRead<?> vr)) continue;

            String mapVar = vr.getVariable().getSimpleName();
            // Skip if already determined to be inconsistent
            if (Boolean.FALSE.equals(consistent.get(mapVar))) continue;

            Optional<FieldProvenance> keyProv   = ProvenanceResolver.resolve(inv.getArguments().get(0), ctx);
            Optional<FieldProvenance> valueProv = ProvenanceResolver.resolve(inv.getArguments().get(1), ctx);

            if (keyProv.isEmpty() || valueProv.isEmpty()) continue;

            if (!keySeenMap.containsKey(mapVar)) {
                keySeenMap.put(mapVar,   keyProv.get());
                valueSeenMap.put(mapVar, valueProv.get());
                consistent.put(mapVar, Boolean.TRUE);
            } else {
                // Check consistency: if a later put() uses a different origin, discard
                boolean sameKey   = keySeenMap.get(mapVar).isSameOrigin(keyProv.get());
                boolean sameValue = valueSeenMap.get(mapVar).isSameOrigin(valueProv.get());
                if (!sameKey || !sameValue) {
                    consistent.put(mapVar, Boolean.FALSE);
                }
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

    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        return List.of();
    }
}
