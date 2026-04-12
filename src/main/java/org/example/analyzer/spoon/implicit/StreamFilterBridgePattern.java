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
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2 pattern: detects implicit field equality from stream filter predicates.
 *
 * Recognized forms (both orderings of equals):
 *   stream.filter(x -> x.getF().equals(outerVar))
 *   stream.filter(x -> outerVar.equals(x.getF()))
 *   stream.filter(x -> x.getF().equals(other.getG()))
 *
 * In all cases, the two sides of the equals() call represent the same value domain,
 * so the fields involved are implicitly equal (MAP_JOIN · READ_PREDICATE).
 *
 * Example:
 *   users.stream()
 *       .filter(u -> u.getDeptCode().equals(employee.getDepartment()))
 *       .collect(toList());
 *   → User.deptCode ≡ Employee.department
 */
public class StreamFilterBridgePattern implements KeyUsagePattern {

    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {}

    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        List<FieldMapping> results = new ArrayList<>();
        String location = method.getSimpleName() + "(stream-filter-equals)";

        for (CtInvocation<?> filterInv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"filter".equals(filterInv.getExecutable().getSimpleName())) continue;
            if (filterInv.getArguments().isEmpty()) continue;
            if (!(filterInv.getArguments().get(0) instanceof CtLambda<?> lambda)) continue;

            // Scan the lambda body for .equals() calls
            for (CtInvocation<?> equalsInv : lambda.getElements(new TypeFilter<>(CtInvocation.class))) {
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

    private ExpressionSide toSide(FieldProvenance prov) {
        return new ExpressionSide(List.of(new FieldRef(prov.originClass(), prov.originField())), "direct");
    }
}
