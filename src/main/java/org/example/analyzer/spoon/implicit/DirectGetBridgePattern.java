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
import spoon.reflect.declaration.CtExecutable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2 detector: general bridge detector for any {@code map.get(expr)} call
 * where the argument has resolvable field provenance.
 *
 * Handles two argument forms:
 *   map.get(obj.getField())   — getter expression directly as argument
 *   map.get(localVar)         — variable whose provenance was registered by a Phase 1 collector
 *
 * Intentionally excludes forEach-lambda parameters (handled by {@link ForEachGetBridgePattern}).
 *
 * Cross-file Maps are resolved via {@link CrossFileMapResolver}:
 *   - the target map may be a field, parameter, getter call, or static of another class.
 *
 */
public class DirectGetBridgePattern implements BridgeDetector {

    @Override
    public List<FieldMapping> detectBridges(MethodScanResult scan,
                                             ProvenanceContext ctx,
                                             CrossFileMapResolver resolver) {
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> inv : scan.invocations) {
            if (!isMapGet(inv)) continue;
            if (isInsideForEachLambda(inv)) continue;

            Optional<MapFact> mapFact = resolver.resolve(inv.getTarget(), ctx);
            if (mapFact.isEmpty()) continue;

            CtExpression<?> arg = inv.getArguments().get(0);
            Optional<FieldProvenance> argProv = ProvenanceResolver.resolve(arg, ctx);
            if (argProv.isEmpty()) continue;

            FieldProvenance mapKey = mapFact.get().keyProvenance();
            if (argProv.get().isSameOrigin(mapKey)) continue;

            String location = resolveLocation(inv);

            results.add(new FieldMapping(
                    toSide(argProv.get()),
                    toSide(mapKey),
                    MappingType.MAP_JOIN,
                    MappingMode.READ_PREDICATE,
                    inv.toString(),
                    location
            ));
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isMapGet(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return (name.equals("get") || name.equals("getOrDefault") || name.equals("computeIfAbsent"))
                && !inv.getArguments().isEmpty()
                && inv.getTarget() != null;
    }

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
                if (parent instanceof CtExecutable<?> && !(parent instanceof CtLambda<?>)) break;
                parent = parent.getParent();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String resolveLocation(CtInvocation<?> inv) {
        try {
            CtExecutable<?> method = inv.getParent(CtExecutable.class);
            return (method != null ? method.getSimpleName() : "unknown") + "(implicit-map-join)";
        } catch (Exception e) {
            return "unknown(implicit-map-join)";
        }
    }

    private ExpressionSide toSide(FieldProvenance prov) {
        return new ExpressionSide(
                List.of(new FieldRef(prov.originClass(), prov.originField())), "direct");
    }
}
