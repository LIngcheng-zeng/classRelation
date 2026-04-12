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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2 detector: detects implicit field equality from Map.forEach + Map.get bridges.
 *
 * Recognized form:
 *   map1.forEach((k, v) -> {
 *       map2.get(k);
 *   });
 *
 * When {@code map1.keyField ≠ map2.keyField} but the same variable is used as the
 * lookup key, the two key fields are implicitly equal (MAP_JOIN relationship).
 *
 * Cross-file Maps are resolved via {@link CrossFileMapResolver}:
 *   - map1 or map2 may be a field, parameter, or static of another class.
 */
public class ForEachGetBridgePattern implements BridgeDetector {

    @Override
    public List<FieldMapping> detectBridges(MethodScanResult scan,
                                             ProvenanceContext ctx,
                                             CrossFileMapResolver resolver) {
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> inv : scan.invocations) {
            if (!isForEach(inv)) continue;

            Optional<MapFact> outerFact = resolver.resolve(inv.getTarget(), ctx);
            if (outerFact.isEmpty()) continue;

            if (inv.getArguments().isEmpty()) continue;
            CtExpression<?> lambdaExpr = inv.getArguments().get(0);
            if (!(lambdaExpr instanceof CtLambda<?> lambda)) continue;
            if (lambda.getParameters().isEmpty()) continue;

            String keyParamName = lambda.getParameters().get(0).getSimpleName();
            FieldProvenance keyParamProvenance = outerFact.get().keyProvenance();

            ProvenanceContext lambdaCtx = ctx.enterScope(ctx.execCtx());
            lambdaCtx.registerVarProvenance(keyParamName, keyParamProvenance);

            if (lambda.getParameters().size() >= 2) {
                String valueParamName = lambda.getParameters().get(1).getSimpleName();
                lambdaCtx.registerVarProvenance(valueParamName, outerFact.get().valueProvenance());
            }

            // Scan lambda body for map.get(keyParam) — use a targeted sub-scan
            for (CtInvocation<?> innerInv : lambda.getElements(
                    new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class))) {
                if (!isMapGet(innerInv)) continue;
                if (innerInv.getArguments().isEmpty()) continue;

                CtExpression<?> getArg = innerInv.getArguments().get(0);
                if (!isVariableRef(getArg, keyParamName)) continue;

                Optional<MapFact> innerFact = resolver.resolve(innerInv.getTarget(), lambdaCtx);
                if (innerFact.isEmpty()) continue;

                FieldProvenance innerKey = innerFact.get().keyProvenance();
                if (keyParamProvenance.isSameOrigin(innerKey)) continue;

                results.add(new FieldMapping(
                        toSide(keyParamProvenance),
                        toSide(innerKey),
                        MappingType.MAP_JOIN,
                        MappingMode.READ_PREDICATE,
                        inv.toString(),
                        resolveLocation(inv)
                ));
            }
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isForEach(CtInvocation<?> inv) {
        return "forEach".equals(inv.getExecutable().getSimpleName());
    }

    private boolean isMapGet(CtInvocation<?> inv) {
        return "get".equals(inv.getExecutable().getSimpleName())
                && inv.getArguments().size() == 1;
    }

    private boolean isVariableRef(CtExpression<?> expr, String varName) {
        return expr instanceof CtVariableRead<?> vr
                && vr.getVariable().getSimpleName().equals(varName);
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
