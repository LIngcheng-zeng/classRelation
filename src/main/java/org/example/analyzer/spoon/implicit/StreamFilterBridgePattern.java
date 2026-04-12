package org.example.analyzer.spoon.implicit;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldProvenance;
import org.example.model.FieldRef;
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
 * Phase 2 detector: detects implicit field equality from stream filter predicates.
 *
 * Recognized forms (both orderings):
 *   stream.filter(x -> x.getF().equals(outerVar))
 *   stream.filter(x -> outerVar.equals(x.getF()))
 *   stream.filter(x -> x.getF().equals(other.getG()))
 *
 * In all cases the two sides of equals() belong to the same value domain,
 * so the fields are implicitly equal (MAP_JOIN · READ_PREDICATE).
 *
 * Note: this detector does not use Maps at all — it detects equality from
 * filter predicates. The {@link CrossFileMapResolver} parameter is accepted
 * for interface consistency but is not used.
 */
public class StreamFilterBridgePattern implements BridgeDetector {

    @Override
    public List<FieldMapping> detectBridges(MethodScanResult scan,
                                             ProvenanceContext ctx,
                                             CrossFileMapResolver resolver) {
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> filterInv : scan.invocations) {
            if (!"filter".equals(filterInv.getExecutable().getSimpleName())) continue;
            if (filterInv.getArguments().isEmpty()) continue;
            if (!(filterInv.getArguments().get(0) instanceof CtLambda<?> lambda)) continue;

            String location = resolveLocation(filterInv);

            for (CtInvocation<?> equalsInv : lambda.getElements(
                    new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class))) {
                if (!"equals".equals(equalsInv.getExecutable().getSimpleName())) continue;
                if (equalsInv.getArguments().isEmpty()) continue;

                CtExpression<?> receiver = equalsInv.getTarget();
                CtExpression<?> argument = equalsInv.getArguments().get(0);
                if (receiver == null) continue;

                Optional<FieldProvenance> leftProv  = ProvenanceResolver.resolve(receiver, ctx);
                Optional<FieldProvenance> rightProv = ProvenanceResolver.resolve(argument, ctx);

                if (leftProv.isEmpty() || rightProv.isEmpty()) continue;
                if (leftProv.get().isSameOrigin(rightProv.get())) continue;

                results.add(new FieldMapping(
                        toSide(leftProv.get()),
                        toSide(rightProv.get()),
                        MappingType.MAP_JOIN,
                        MappingMode.READ_PREDICATE,
                        equalsInv.toString(),
                        location
                ));
            }
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String resolveLocation(CtInvocation<?> inv) {
        try {
            CtExecutable<?> method = inv.getParent(CtExecutable.class);
            return (method != null ? method.getSimpleName() : "unknown") + "(stream-filter-equals)";
        } catch (Exception e) {
            return "unknown(stream-filter-equals)";
        }
    }

    private ExpressionSide toSide(FieldProvenance prov) {
        return new ExpressionSide(
                List.of(new FieldRef(prov.originClass(), prov.originField())), "direct");
    }
}
