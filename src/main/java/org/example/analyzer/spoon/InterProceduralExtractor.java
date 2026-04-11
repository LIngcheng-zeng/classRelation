package org.example.analyzer.spoon;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts inter-procedural field mappings by projecting caller arguments
 * into callee parameter names and analyzing the callee body.
 *
 * Algorithm per method M with aliasMap A:
 *   1. Find all CtInvocations in M's body.
 *   2. Resolve each invocation's callee CtExecutable.
 *   3. Build a projected alias map: callee_param → resolved caller argument expression.
 *      Only arguments that trace to a field reference (via A) are projected.
 *   4. Analyze the callee body using the projected map:
 *      - setter calls   (delegated to {@link DirectSetterExtractor})
 *      - field writes   (target.field = value)
 *      - constructor calls (delegated to {@link ConstructorCallExtractor})
 *   5. Recurse into callees (depth-limited, cycle-guarded).
 *
 * Example:
 *   Caller:  orderId = order.orderId;  generateInvoice(user, orderId);
 *   Callee:  void generateInvoice(User user, String orderId) { invoice.setRefOrderId(orderId); }
 *   Output:  Order.orderId → Invoice.refOrderId
 */
class InterProceduralExtractor {

    private static final int MAX_DEPTH = 3;

    private final SpoonResolutionHelper    helper;
    private final DirectSetterExtractor    setterExtractor = new DirectSetterExtractor();
    private final ConstructorCallExtractor ctorExtractor   = new ConstructorCallExtractor();

    InterProceduralExtractor(SpoonResolutionHelper helper) {
        this.helper = helper;
    }

    List<FieldMapping> extract(CtExecutable<?> method, Map<String, CtExpression<?>> aliasMap) {
        List<FieldMapping> results = new ArrayList<>();
        processInvocations(method, aliasMap, 0, new HashSet<>(), results);
        return results;
    }

    // -------------------------------------------------------------------------

    private void processInvocations(CtExecutable<?> method,
                                     Map<String, CtExpression<?>> aliasMap,
                                     int depth, Set<String> visited,
                                     List<FieldMapping> results) {
        if (depth > MAX_DEPTH) return;

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutable<?> callee = resolveCallee(inv);
            if (callee == null) continue;

            String calleeKey = calleeKey(callee);
            if (visited.contains(calleeKey)) continue;

            Map<String, CtExpression<?>> projected = buildProjectedAlias(inv, callee, aliasMap);
            if (projected.isEmpty()) continue;

            extractFromCallee(callee, projected, results);

            Set<String> childVisited = new HashSet<>(visited);
            childVisited.add(calleeKey);
            processInvocations(callee, projected, depth + 1, childVisited, results);
        }
    }

    /**
     * Applies all intra-procedural patterns to {@code callee} using the projected alias map.
     * Location tag uses "(projected)" to distinguish from top-level intra-procedural results.
     */
    private void extractFromCallee(CtExecutable<?> callee,
                                    Map<String, CtExpression<?>> projected,
                                    List<FieldMapping> results) {
        String location = callee.getSimpleName() + "(projected)";

        // Pattern 1: setter calls — reuse DirectSetterExtractor logic but re-tag location
        for (CtInvocation<?> inv : callee.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!helper.isSetter(inv)) continue;

            String rawName   = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);

            String         sinkClass  = helper.resolveClassName(inv.getTarget());
            ExpressionSide sinkSide   = new ExpressionSide(List.of(new FieldRef(sinkClass, fieldName)), "direct");
            ExpressionSide sourceSide = helper.extractSourceSide(inv.getArguments().get(0), projected);
            if (!helper.isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(sourceSide, sinkSide,
                    MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                    inv.toString(), location));
        }

        // Pattern 2: direct field writes — target.field = value
        for (CtAssignment<?, ?> assign : callee.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (!(assign.getAssigned() instanceof CtFieldWrite<?> fw)) continue;

            String         sinkClass  = helper.resolveClassName(fw.getTarget());
            ExpressionSide sinkSide   = new ExpressionSide(
                    List.of(new FieldRef(sinkClass, fw.getVariable().getSimpleName())), "direct");
            ExpressionSide sourceSide = helper.extractSourceSide(assign.getAssignment(), projected);
            if (!helper.isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(sourceSide, sinkSide,
                    MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                    assign.toString(), location));
        }

        // Pattern 3: constructor calls — delegate to ConstructorCallExtractor
        results.addAll(ctorExtractor.extract(callee, projected, helper));
    }

    // -------------------------------------------------------------------------
    // Alias projection helpers
    // -------------------------------------------------------------------------

    /**
     * For each callee parameter, maps it to the resolved caller argument expression
     * if that argument carries field provenance.
     */
    private Map<String, CtExpression<?>> buildProjectedAlias(CtInvocation<?> inv,
                                                               CtExecutable<?> callee,
                                                               Map<String, CtExpression<?>> callerAlias) {
        Map<String, CtExpression<?>> projected = new LinkedHashMap<>();

        List<CtParameter<?>> params = callee.getParameters();
        List<CtExpression<?>> args  = (List<CtExpression<?>>) (List<?>) inv.getArguments();
        int limit = Math.min(params.size(), args.size());

        for (int i = 0; i < limit; i++) {
            CtExpression<?> resolved = resolveAlias(args.get(i), callerAlias, new HashSet<>());
            if (hasFieldProvenance(resolved)) {
                projected.put(params.get(i).getSimpleName(), resolved);
            }
        }

        return projected;
    }

    private CtExpression<?> resolveAlias(CtExpression<?> expr,
                                          Map<String, CtExpression<?>> aliasMap,
                                          Set<String> visited) {
        if (expr instanceof CtVariableRead<?> vr) {
            String name = vr.getVariable().getSimpleName();
            if (!visited.contains(name) && aliasMap.containsKey(name)) {
                visited.add(name);
                return resolveAlias(aliasMap.get(name), aliasMap, visited);
            }
        }
        return expr;
    }

    private boolean hasFieldProvenance(CtExpression<?> expr) {
        if (expr instanceof spoon.reflect.code.CtFieldRead<?>) return true;
        if (expr instanceof CtInvocation<?> inv && helper.isGetter(inv)) return true;

        if (expr instanceof CtInvocation<?> inv) {
            CtExpression<?> target = inv.getTarget();
            while (target instanceof CtInvocation<?> targetInv) {
                if (helper.isGetter(targetInv)) return true;
                target = targetInv.getTarget();
            }
            if (target instanceof spoon.reflect.code.CtFieldRead<?>) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------

    private CtExecutable<?> resolveCallee(CtInvocation<?> inv) {
        try {
            CtExecutable<?> decl = inv.getExecutable().getDeclaration();
            return (decl != null && decl.getBody() != null) ? decl : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String calleeKey(CtExecutable<?> callee) {
        String typeName = (callee instanceof CtTypeMember tm && tm.getDeclaringType() != null)
                ? tm.getDeclaringType().getQualifiedName()
                : "?";
        return typeName + "#" + callee.getSignature();
    }
}
