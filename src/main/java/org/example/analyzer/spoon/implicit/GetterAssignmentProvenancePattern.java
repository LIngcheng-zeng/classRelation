package org.example.analyzer.spoon.implicit;

import org.example.model.FieldMapping;
import org.example.model.FieldProvenance;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Optional;

/**
 * Phase 1 pattern: registers field provenance for local variables assigned from getter calls.
 *
 * Recognized forms:
 *   String x = user.getId();          → varProvenance["x"] = FieldProvenance("User", "id")
 *   String y = order.getStatus();     → varProvenance["y"] = FieldProvenance("Order", "status")
 *   x = entity.getCode();             → re-assignment also tracked
 *
 * This enables {@link DirectGetBridgePattern} to resolve provenance when a variable
 * (rather than a getter call) is passed to {@code map.get(var)}.
 *
 * Example end-to-end:
 *   String key = invoice.getOrderNo();
 *   Order found = orderMap.get(key);
 *   // → DirectGetBridgePattern sees key's provenance = Invoice.orderNo
 *   // → orderMap.keyField = Order.orderNo (from MapFact)
 *   // → infers: Invoice.orderNo ≡ Order.orderNo
 */
public class GetterAssignmentProvenancePattern implements KeyUsagePattern {

    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {
        // Local variable declarations:  Type x = obj.getField();
        for (CtLocalVariable<?> lv : method.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!(lv.getDefaultExpression() instanceof CtInvocation<?> inv)) continue;
            Optional<FieldProvenance> prov = ProvenanceResolver.fromGetter(inv);
            prov.ifPresent(p -> ctx.registerVarProvenance(lv.getSimpleName(), p));
        }

        // Re-assignments:  x = obj.getField();
        for (CtAssignment<?, ?> assign : method.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (!(assign.getAssigned() instanceof CtVariableWrite<?> vw)) continue;
            if (!(assign.getAssignment() instanceof CtInvocation<?> inv)) continue;
            Optional<FieldProvenance> prov = ProvenanceResolver.fromGetter(inv);
            prov.ifPresent(p -> ctx.registerVarProvenance(vw.getVariable().getSimpleName(), p));
        }
    }

    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        return List.of();
    }
}
