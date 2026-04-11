package org.example.analyzer.spoon.intra;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.analyzer.spoon.SpoonPatternExtractor;
import org.example.analyzer.spoon.SpoonResolutionHelper;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects field mappings encoded in Lombok / manual builder call chains.
 *
 * Scans the entire method body (including nested lambda bodies) for any
 * invocation that terminates with {@code .build()}. The chain is walked inward;
 * every intermediate method call that is neither {@code builder()} nor {@code build()}
 * is treated as a field setter whose argument is resolved to source FieldRefs.
 *
 * Example (direct):
 *   {@code Address.builder().city(order.getCity()).zip(order.getZip()).build()}
 *   → Order.city → Address.city
 *   → Order.zip  → Address.zip
 *
 * Example (lambda-nested):
 *   {@code items.stream().map(item -> ItemDetail.builder().item(item).build())}
 *   → Item.item → ItemDetail.item   (item resolved via Spoon type inference)
 */
public class BuilderChainExtractor implements SpoonPatternExtractor {

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      ExecutionContext ctx,
                                      SpoonResolutionHelper helper) {
        String location = method.getSimpleName() + "(builder)";
        List<FieldMapping> results = new ArrayList<>();

        // Scan ALL .build() invocations in the method body, including lambda-nested ones.
        // VariableTypeResolver in the chain handles lambda params via Spoon type inference,
        // so the method-level ctx is sufficient even for nested builder chains.
        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!inv.getExecutable().getSimpleName().equals("build")) continue;

            String targetClass = extractBuilderTargetClass(inv);
            if (targetClass != null) {
                walkChain(inv, targetClass, ctx, helper, location, results);
            }
        }

        return results;
    }

    // -------------------------------------------------------------------------

    private String extractBuilderTargetClass(CtExpression<?> expr) {
        CtExpression<?> current = expr;
        while (current instanceof CtInvocation<?> inv) {
            if (inv.getExecutable().getSimpleName().equals("builder") && inv.getTarget() != null) {
                CtExpression<?> builderTarget = inv.getTarget();
                if (builderTarget instanceof CtTypeAccess<?> ta) {
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
                            ExecutionContext ctx, SpoonResolutionHelper helper,
                            String location, List<FieldMapping> results) {
        if (!(expr instanceof CtInvocation<?> inv)) return;

        String methodName = inv.getExecutable().getSimpleName();

        if (methodName.equals("build") || methodName.equals("builder")) {
            walkChain(inv.getTarget(), targetClass, ctx, helper, location, results);
            return;
        }

        if (!inv.getArguments().isEmpty()) {
            CtExpression<?> arg = inv.getArguments().get(0);
            ExpressionSide sourceSide = helper.extractSourceSide(arg, ctx);
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

        walkChain(inv.getTarget(), targetClass, ctx, helper, location, results);
    }
}
