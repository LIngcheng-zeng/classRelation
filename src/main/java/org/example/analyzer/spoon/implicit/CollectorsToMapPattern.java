package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;

import java.util.Optional;

/**
 * Phase 1 collector: extracts MapFacts from {@code Collectors.toMap(KeyGetter, ValueGetter)} calls.
 *
 * Recognized form:
 *   Map<K, V> varName = someStream.collect(Collectors.toMap(A::getX, A::getY));
 *
 * Produces:
 *   MapFact(varName, keyProvenance=FieldProvenance(A, x), valueProvenance=FieldProvenance(A, y))
 *
 * Phase 2 bridge detection is handled by {@link ForEachGetBridgePattern} and
 * {@link DirectGetBridgePattern} after all MapFacts are known.
 */
public class CollectorsToMapPattern implements MapFactCollector {

    @Override
    public void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx, GlobalMapRegistry globalRegistry) {
        for (CtLocalVariable<?> lv : scan.localVars) {
            if (lv.getDefaultExpression() == null) continue;
            tryExtract(lv.getSimpleName(), lv.getDefaultExpression(), ctx);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void tryExtract(String varName, CtExpression<?> initExpr, ProvenanceContext ctx) {
        CtInvocation<?> collectCall = findCollectCall(initExpr);
        if (collectCall == null) return;
        if (collectCall.getArguments().isEmpty()) return;

        CtExpression<?> collectorArg = collectCall.getArguments().get(0);
        if (!(collectorArg instanceof CtInvocation<?> toMapCall)) return;
        if (!"toMap".equals(toMapCall.getExecutable().getSimpleName())) return;
        if (toMapCall.getArguments().size() < 2) return;

        // Delegate to shared ProvenanceResolver — eliminates the old private duplicate
        Optional<FieldProvenance> keyProv   = ProvenanceResolver.fromMethodRef(toMapCall.getArguments().get(0));
        Optional<FieldProvenance> valueProv = ProvenanceResolver.fromMethodRef(toMapCall.getArguments().get(1));
        if (keyProv.isEmpty() || valueProv.isEmpty()) return;

        MapFact fact = MapFact.of(varName, keyProv.get(), valueProv.get(), toMapCall.toString());
        ctx.registerMapFact(fact);
        ctx.registerVarProvenance(varName + "#key",   keyProv.get());
        ctx.registerVarProvenance(varName + "#value", valueProv.get());
    }

    private CtInvocation<?> findCollectCall(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv)) return null;
        if ("collect".equals(inv.getExecutable().getSimpleName())) return inv;
        if (inv.getTarget() != null) return findCollectCall(inv.getTarget());
        return null;
    }
}
