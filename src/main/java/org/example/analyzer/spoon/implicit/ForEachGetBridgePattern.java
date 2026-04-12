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
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2 pattern: detects implicit field equality from Map.forEach + Map.get bridges.
 *
 * Recognized form:
 *   map1.forEach((k, v) -> {
 *       map2.get(k);   // k came from map1's key; map2's key is a different field
 *   });
 *
 * When {@code map1.keyField ≠ map2.keyField} but the same variable is used as the
 * lookup key, the two key fields are implicitly equal (MAP_JOIN relationship).
 *
 * Two FieldMappings are emitted per bridge:
 *   1. The key equality:   map1.keyField ≡ map2.keyField
 *   2. The value mapping:  map1.valueField → map2.valueField  (derived association)
 */
public class ForEachGetBridgePattern implements KeyUsagePattern {

    // Phase 1 is not the responsibility of this pattern.
    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {}

    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!isForEach(inv)) continue;

            // Resolve the Map variable the forEach is called on
            Optional<MapFact> outerFact = resolveMapFact(inv.getTarget(), ctx);
            if (outerFact.isEmpty()) continue;

            // The forEach argument must be a lambda with ≥ 1 parameter
            if (inv.getArguments().isEmpty()) continue;
            CtExpression<?> lambdaExpr = inv.getArguments().get(0);
            if (!(lambdaExpr instanceof CtLambda<?> lambda)) continue;
            if (lambda.getParameters().isEmpty()) continue;

            // Key parameter is the first lambda parameter
            String keyParamName = lambda.getParameters().get(0).getSimpleName();
            FieldProvenance keyParamProvenance = outerFact.get().keyProvenance();

            // Register the key param's provenance in a child scope so bridge detection
            // inside the lambda can resolve it
            ProvenanceContext lambdaCtx = ctx.enterScope(ctx.execCtx());
            lambdaCtx.registerVarProvenance(keyParamName, keyParamProvenance);

            // Register value param provenance if present
            if (lambda.getParameters().size() >= 2) {
                String valueParamName = lambda.getParameters().get(1).getSimpleName();
                lambdaCtx.registerVarProvenance(valueParamName, outerFact.get().valueProvenance());
            }

            // Scan the lambda body for map.get(keyParam) calls
            for (CtInvocation<?> innerInv : lambda.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (!isMapGet(innerInv)) continue;
                if (innerInv.getArguments().isEmpty()) continue;

                CtExpression<?> getArg = innerInv.getArguments().get(0);
                if (!isVariableRef(getArg, keyParamName)) continue;

                // Resolve the inner Map's MapFact
                Optional<MapFact> innerFact = resolveMapFact(innerInv.getTarget(), lambdaCtx);
                if (innerFact.isEmpty()) continue;

                FieldProvenance innerKey = innerFact.get().keyProvenance();

                // If inner map's key comes from the same field as the outer key param,
                // there is no new information — skip (same origin).
                if (keyParamProvenance.isSameOrigin(innerKey)) continue;

                String location = method.getSimpleName() + "(implicit-map-join)";
                String rawExpr  = inv.toString();

                // Mapping 1: key equality  outerKeyField ≡ innerKeyField
                results.add(new FieldMapping(
                        toSide(keyParamProvenance),
                        toSide(innerKey),
                        MappingType.MAP_JOIN,
                        MappingMode.READ_PREDICATE,
                        rawExpr,
                        location
                ));

                // Mapping 2: derived value association  outerValue → innerValue
                FieldProvenance outerValue = outerFact.get().valueProvenance();
                FieldProvenance innerValue = innerFact.get().valueProvenance();
                if (!outerValue.isSameOrigin(innerValue)) {
                    results.add(new FieldMapping(
                            toSide(outerValue),
                            toSide(innerValue),
                            MappingType.MAP_JOIN,
                            MappingMode.WRITE_ASSIGNMENT,
                            rawExpr,
                            location
                    ));
                }
            }
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Optional<MapFact> resolveMapFact(CtExpression<?> target, ProvenanceContext ctx) {
        if (target == null) return Optional.empty();
        if (target instanceof CtVariableRead<?> vr) {
            return ctx.mapFact(vr.getVariable().getSimpleName());
        }
        return Optional.empty();
    }

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

    private ExpressionSide toSide(FieldProvenance prov) {
        return new ExpressionSide(
                List.of(new FieldRef(prov.originClass(), prov.originField())),
                "direct"
        );
    }
}
