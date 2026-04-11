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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects field mappings encoded in Lombok / manual builder call chains.
 *
 * Pattern: an alias-map entry whose initializer terminates with {@code .build()}.
 * The chain is walked inward; every intermediate method call that is neither
 * {@code builder()} nor {@code build()} is treated as a field setter.
 *
 * Example:
 *   {@code Address.builder().city(order.getCity()).zip(order.getZip()).build()}
 *   → Order.city → Address.city
 *   → Order.zip  → Address.zip
 */
public class BuilderChainExtractor implements SpoonPatternExtractor {

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      Map<String, CtExpression<?>> aliasMap,
                                      SpoonResolutionHelper helper) {
        String location = method.getSimpleName() + "(builder)";
        List<FieldMapping> results = new ArrayList<>();

        for (CtExpression<?> expr : aliasMap.values()) {
            if (!isBuilderChain(expr)) continue;

            String targetClass = extractBuilderTargetClass(expr);
            if (targetClass != null) {
                walkChain(expr, targetClass, aliasMap, helper, location, results);
            }
        }

        return results;
    }

    // -------------------------------------------------------------------------

    private boolean isBuilderChain(CtExpression<?> expr) {
        return expr instanceof CtInvocation<?> inv
                && inv.getExecutable().getSimpleName().equals("build");
    }

    private String extractBuilderTargetClass(CtExpression<?> expr) {
        CtExpression<?> current = expr;
        while (current instanceof CtInvocation<?> inv) {
            if (inv.getExecutable().getSimpleName().equals("builder") && inv.getTarget() != null) {
                CtExpression<?> builderTarget = inv.getTarget();
                if (builderTarget instanceof spoon.reflect.code.CtTypeAccess<?> ta) {
                    try {
                        String qualified = ta.getAccessedType().getQualifiedName();
                        int dot = qualified.lastIndexOf('.');
                        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
                    } catch (Exception ignored) {}
                }
                String raw = builderTarget.toString();
                if (!raw.isEmpty() && Character.isUpperCase(raw.charAt(0))
                        && !raw.contains("(") && !raw.contains(".")) return raw;
            }
            current = inv.getTarget();
        }
        if (expr instanceof CtInvocation<?> inv) {
            try {
                String qualified = inv.getType().getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                String simple = dot >= 0 ? qualified.substring(dot + 1) : qualified;
                if (!simple.isEmpty() && !simple.contains("<")) return simple;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void walkChain(CtExpression<?> expr, String targetClass,
                            Map<String, CtExpression<?>> aliasMap, SpoonResolutionHelper helper,
                            String location, List<FieldMapping> results) {
        if (!(expr instanceof CtInvocation<?> inv)) return;

        String methodName = inv.getExecutable().getSimpleName();

        if (methodName.equals("build") || methodName.equals("builder")) {
            walkChain(inv.getTarget(), targetClass, aliasMap, helper, location, results);
            return;
        }

        if (!inv.getArguments().isEmpty()) {
            CtExpression<?> arg = inv.getArguments().get(0);
            ExpressionSide sourceSide = helper.extractSourceSide(arg, aliasMap);
            if (!sourceSide.isEmpty()) {
                FieldRef sinkRef = new FieldRef(targetClass, methodName);
                ExpressionSide sinkSide = new ExpressionSide(List.of(sinkRef), "direct");
                if (helper.isValidPair(sourceSide, sinkSide)) {
                    results.add(new FieldMapping(sourceSide, sinkSide,
                            MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                            inv.toString(), location));
                }
            }
        }

        walkChain(inv.getTarget(), targetClass, aliasMap, helper, location, results);
    }
}
