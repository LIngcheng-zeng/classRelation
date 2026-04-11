package org.example.analyzer.spoon;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects composition / aggregation relationships by examining getter return types.
 *
 * Pattern: any getter call {@code holderObj.getSomething()} where the return type is a
 * non-system user class indicates that {@code HolderClass} holds (aggregates) that returned class.
 *
 * Example:
 *   {@code userOrderDTO.getOrderDTO()}  →  {@code UserOrderDTO} holds {@code OrderDTO}
 *   Emits: FieldMapping(UserOrderDTO.holds → OrderDTO.held)
 */
class CompositionExtractor implements SpoonPatternExtractor {

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      Map<String, CtExpression<?>> aliasMap,
                                      SpoonResolutionHelper helper) {
        String location = method.getSimpleName() + "(composition)";
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!helper.isGetter(inv)) continue;

            try {
                CtExpression<?> target = inv.getTarget();
                if (target == null) continue;

                String holderClass = helper.resolveClassName(target);
                if (holderClass == null || helper.isSystemClass(holderClass)) continue;

                String heldClass = helper.resolveGetterReturnType(inv);
                if (heldClass == null || helper.isSystemClass(heldClass)) continue;

                if (holderClass.equals(heldClass)) continue;  // no self-reference

                ExpressionSide sourceSide = new ExpressionSide(
                        List.of(new FieldRef(holderClass, "holds")), "composition-source");
                ExpressionSide sinkSide = new ExpressionSide(
                        List.of(new FieldRef(heldClass, "held")), "composition-sink");

                results.add(new FieldMapping(sourceSide, sinkSide,
                        MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                        inv.toString(), location));

            } catch (Exception ignored) {}
        }

        return results;
    }
}
