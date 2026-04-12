package org.example.analyzer.spoon.implicit;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldProvenance;
import org.example.model.FieldRef;
import org.example.model.MapFact;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2 pattern: general bridge detector for any {@code map.get(expr)} call
 * where the argument has resolvable field provenance.
 *
 * Handles two argument forms:
 *   map.get(obj.getField())   — getter expression directly as argument
 *   map.get(localVar)         — variable whose provenance was registered by a Phase 1 pattern
 *
 * Intentionally excludes forEach-lambda parameters (those are handled by
 * {@link ForEachGetBridgePattern}, which creates the necessary lambda-scoped provenance).
 *
 * For each call where:
 *   - the target map has a MapFact in the ProvenanceContext
 *   - the argument has resolvable provenance
 *   - the argument's origin differs from the map's key origin
 *
 * Emits two FieldMappings:
 *   1. Key equality:   argField ≡ mapKeyField       [MAP_JOIN · READ_PREDICATE]
 *   2. Value mapping:  argField source → mapValueField  [MAP_JOIN · WRITE_ASSIGNMENT]
 *      (only when the value has a concrete field, not a "#self" sentinel)
 */
public class DirectGetBridgePattern implements KeyUsagePattern {

    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {}

    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        List<FieldMapping> results = new ArrayList<>();
        String location = method.getSimpleName() + "(implicit-map-join)";

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!isMapGet(inv)) continue;
            if (isInsideForEachLambda(inv)) continue;   // handled by ForEachGetBridgePattern

            Optional<MapFact> mapFact = resolveMapFact(inv.getTarget(), ctx);
            if (mapFact.isEmpty()) continue;

            CtExpression<?> arg = inv.getArguments().get(0);
            Optional<FieldProvenance> argProv = ProvenanceResolver.resolve(arg, ctx);
            if (argProv.isEmpty()) continue;

            FieldProvenance mapKey = mapFact.get().keyProvenance();
            if (argProv.get().isSameOrigin(mapKey)) continue;   // same origin — no new info

            // Key equality
            results.add(new FieldMapping(
                    toSide(argProv.get()),
                    toSide(mapKey),
                    MappingType.MAP_JOIN,
                    MappingMode.READ_PREDICATE,
                    inv.toString(),
                    location
            ));

            // Value derivation (skip "#self" sentinel used by groupingBy)
            FieldProvenance mapValue = mapFact.get().valueProvenance();
            if (!mapValue.originField().equals("#self")) {
                results.add(new FieldMapping(
                        toSide(argProv.get()),
                        toSide(mapValue),
                        MappingType.MAP_JOIN,
                        MappingMode.WRITE_ASSIGNMENT,
                        inv.toString(),
                        location
                ));
            }
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isMapGet(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        // get, getOrDefault, computeIfAbsent all use the key as first arg
        return (name.equals("get") || name.equals("getOrDefault") || name.equals("computeIfAbsent"))
                && !inv.getArguments().isEmpty()
                && inv.getTarget() != null;
    }

    /**
     * Returns true if this invocation is directly inside a Map.forEach lambda.
     * Such sites are already processed by {@link ForEachGetBridgePattern}.
     */
    private boolean isInsideForEachLambda(CtInvocation<?> inv) {
        try {
            var parent = inv.getParent();
            while (parent != null) {
                if (parent instanceof CtLambda<?> lambda) {
                    var grandParent = lambda.getParent();
                    if (grandParent instanceof CtInvocation<?> outerInv
                            && "forEach".equals(outerInv.getExecutable().getSimpleName())) {
                        return true;
                    }
                }
                // Stop at method boundary
                if (parent instanceof CtExecutable<?> && !(parent instanceof CtLambda<?>)) break;
                parent = parent.getParent();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Optional<MapFact> resolveMapFact(CtExpression<?> target, ProvenanceContext ctx) {
        if (target instanceof CtVariableRead<?> vr) {
            return ctx.mapFact(vr.getVariable().getSimpleName());
        }
        return Optional.empty();
    }

    private ExpressionSide toSide(FieldProvenance prov) {
        return new ExpressionSide(List.of(new FieldRef(prov.originClass(), prov.originField())), "direct");
    }
}
