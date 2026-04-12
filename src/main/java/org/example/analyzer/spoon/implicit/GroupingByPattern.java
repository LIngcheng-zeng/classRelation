package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;

import java.util.Optional;

/**
 * Phase 1 collector: extracts MapFacts from {@code Collectors.groupingBy(KeyGetter)} calls.
 *
 * Recognized form:
 *   Map<K, List<V>> varName = stream.collect(Collectors.groupingBy(A::getField));
 *
 * Produces:
 *   MapFact(varName, keyProvenance=FieldProvenance(A, field),
 *                    valueProvenance=FieldProvenance(A, #self))
 *
 * Bridge detection is handled by {@link DirectGetBridgePattern}.
 */
public class GroupingByPattern implements MapFactCollector {

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
        if (!(collectorArg instanceof CtInvocation<?> groupingCall)) return;

        String callee = groupingCall.getExecutable().getSimpleName();
        if (!callee.equals("groupingBy") && !callee.equals("partitioningBy")) return;
        if (groupingCall.getArguments().isEmpty()) return;

        Optional<FieldProvenance> keyProv =
                ProvenanceResolver.fromMethodRef(groupingCall.getArguments().get(0));
        if (keyProv.isEmpty()) return;

        FieldProvenance valueProv = FieldProvenance.of(keyProv.get().originClass(), "#self");

        ctx.registerMapFact(MapFact.of(varName, keyProv.get(), valueProv, groupingCall.toString()));
        ctx.registerVarProvenance(varName + "#key", keyProv.get());
    }

    private CtInvocation<?> findCollectCall(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv)) return null;
        if ("collect".equals(inv.getExecutable().getSimpleName())) return inv;
        if (inv.getTarget() != null) return findCollectCall(inv.getTarget());
        return null;
    }
}
