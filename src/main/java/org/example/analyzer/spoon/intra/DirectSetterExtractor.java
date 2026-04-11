package org.example.analyzer.spoon.intra;

import org.example.analyzer.spoon.SpoonPatternExtractor;
import org.example.analyzer.spoon.SpoonResolutionHelper;
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
 * Extracts intra-procedural field mappings from direct setter calls.
 *
 * Pattern: {@code receiver.setXxx(value)}
 *   The receiver determines the sink class; the setter name determines the field.
 *   The value expression is resolved to source FieldRefs via the alias map,
 *   supporting getter chains like {@code orderDTO.getOrder().getUserId()}.
 *
 * Example:
 *   {@code vipUser.setId(orderDTO.getOrder().getUserId())}
 *   → Order.userId → VipUser.id
 */
public class DirectSetterExtractor implements SpoonPatternExtractor {

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      Map<String, CtExpression<?>> aliasMap,
                                      SpoonResolutionHelper helper) {
        String location = method.getSimpleName() + "(direct-setter)";
        List<FieldMapping> results = new ArrayList<>();

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!helper.isSetter(inv)) continue;

            String rawName   = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);

            String         sinkClass  = helper.resolveClassName(inv.getTarget());
            ExpressionSide sinkSide   = new ExpressionSide(
                    List.of(new FieldRef(sinkClass, fieldName)), "direct");
            ExpressionSide sourceSide = helper.extractSourceSide(inv.getArguments().get(0), aliasMap);

            if (!helper.isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(sourceSide, sinkSide,
                    MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                    inv.toString(), location));
        }

        return results;
    }
}
