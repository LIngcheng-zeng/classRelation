package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableWrite;

import java.util.Optional;

/**
 * Phase 1 collector: registers field provenance for local variables assigned from getter calls.
 *
 * Recognized forms:
 *   String x = user.getId();          → varProvenance["x"] = FieldProvenance("User", "id")
 *   String y = order.getStatus();     → varProvenance["y"] = FieldProvenance("Order", "status")
 *   x = entity.getCode();             → re-assignment also tracked
 *
 * Enables {@link DirectGetBridgePattern} to resolve provenance when a variable
 * (rather than a getter call) is passed to {@code map.get(var)}.
 */
public class GetterAssignmentProvenancePattern implements MapFactCollector {

    @Override
    public void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx, GlobalMapRegistry globalRegistry) {
        // Local variable declarations: Type x = obj.getField();
        for (CtLocalVariable<?> lv : scan.localVars) {
            if (!(lv.getDefaultExpression() instanceof CtInvocation<?> inv)) continue;
            Optional<FieldProvenance> prov = ProvenanceResolver.fromGetter(inv);
            prov.ifPresent(p -> ctx.registerVarProvenance(lv.getSimpleName(), p));
        }

        // Re-assignments: x = obj.getField();
        for (CtAssignment<?, ?> assign : scan.assignments) {
            if (!(assign.getAssigned() instanceof CtVariableWrite<?> vw)) continue;
            if (!(assign.getAssignment() instanceof CtInvocation<?> inv)) continue;
            Optional<FieldProvenance> prov = ProvenanceResolver.fromGetter(inv);
            prov.ifPresent(p -> ctx.registerVarProvenance(vw.getVariable().getSimpleName(), p));
        }
    }
}
