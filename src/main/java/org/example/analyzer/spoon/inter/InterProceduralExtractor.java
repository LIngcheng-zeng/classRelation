package org.example.analyzer.spoon.inter;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.analyzer.spoon.SpoonResolutionHelper;
import org.example.analyzer.spoon.intra.ConstructorCallExtractor;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts inter-procedural field mappings by projecting caller arguments
 * into callee parameter names and analyzing the callee body.
 *
 * Algorithm per method M with ExecutionContext ctx:
 *   1. Find all CtInvocations in M's body.
 *   2. Resolve each invocation's callee CtExecutable.
 *   3. Build a callee context via {@code ctx.enterCallee(callee, args)},
 *      projecting call-site args onto callee parameter names.
 *   4. Analyze the callee body using the projected context:
 *      - setter calls   (inline)
 *      - field writes   (inline)
 *      - constructor calls (delegated to {@link ConstructorCallExtractor})
 *   5. Recurse into callees (depth-limited, cycle-guarded).
 */
public class InterProceduralExtractor {

    private static final int MAX_DEPTH = 3;

    private final SpoonResolutionHelper    helper;
    private final ConstructorCallExtractor ctorExtractor = new ConstructorCallExtractor();

    public InterProceduralExtractor(SpoonResolutionHelper helper) {
        this.helper = helper;
    }

    public List<FieldMapping> extract(CtExecutable<?> method, ExecutionContext ctx) {
        List<FieldMapping> results = new ArrayList<>();
        processInvocations(method, ctx, 0, new HashSet<>(), results);
        return results;
    }

    // -------------------------------------------------------------------------

    private void processInvocations(CtExecutable<?> method,
                                     ExecutionContext ctx,
                                     int depth, Set<String> visited,
                                     List<FieldMapping> results) {
        if (depth > MAX_DEPTH) return;

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutable<?> callee = resolveCallee(inv);
            if (callee == null) continue;

            String calleeKey = calleeKey(callee);
            if (visited.contains(calleeKey)) continue;

            // Pre-resolve each argument through the caller binding chain before projecting.
            // This ensures callee sees the underlying field expression, not an intermediate
            // variable read that may not be visible in the projected (parentless) callee context.
            List<CtExpression<?>> resolvedArgs = resolveArgs(inv, ctx);
            ExecutionContext calleeCtx = ctx.enterCallee(callee, resolvedArgs);

            if (calleeCtx.bindings().isEmpty()) continue;

            extractFromCallee(callee, calleeCtx, results);

            Set<String> childVisited = new HashSet<>(visited);
            childVisited.add(calleeKey);
            processInvocations(callee, calleeCtx, depth + 1, childVisited, results);
        }
    }

    private void extractFromCallee(CtExecutable<?> callee,
                                    ExecutionContext calleeCtx,
                                    List<FieldMapping> results) {
        String location = callee.getSimpleName() + "(projected)";

        // Pattern 1: setter calls
        for (CtInvocation<?> inv : callee.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!helper.isSetter(inv)) continue;

            String rawName   = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);

            String         sinkClass  = helper.resolveClassName(inv.getTarget());
            ExpressionSide sinkSide   = new ExpressionSide(List.of(new FieldRef(sinkClass, fieldName)), "direct");
            ExpressionSide sourceSide = helper.extractSourceSide(inv.getArguments().get(0), calleeCtx);
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
            ExpressionSide sourceSide = helper.extractSourceSide(assign.getAssignment(), calleeCtx);
            if (!helper.isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(sourceSide, sinkSide,
                    MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                    assign.toString(), location));
        }

        // Pattern 3: constructor calls
        results.addAll(ctorExtractor.extract(callee, calleeCtx, helper));
    }

    // -------------------------------------------------------------------------
    // Argument pre-resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves each call argument through the caller binding chain.
     * A plain CtVariableRead is unwrapped to its bound expression (recursively),
     * so the projected callee context sees the underlying field/getter, not a
     * stale variable reference that wouldn't be visible in the parentless callee scope.
     */
    private List<CtExpression<?>> resolveArgs(CtInvocation<?> inv, ExecutionContext ctx) {
        List<CtExpression<?>> resolved = new ArrayList<>();
        for (CtExpression<?> arg : inv.getArguments()) {
            resolved.add(resolveArg(arg, ctx, new HashSet<>()));
        }
        return resolved;
    }

    private CtExpression<?> resolveArg(CtExpression<?> expr, ExecutionContext ctx,
                                        Set<String> visited) {
        if (!(expr instanceof spoon.reflect.code.CtVariableRead<?> vr)) return expr;
        String name = vr.getVariable().getSimpleName();
        if (visited.contains(name)) return expr;
        java.util.Optional<CtExpression<?>> bound = ctx.binding(name);
        if (bound.isEmpty()) return expr;
        Set<String> next = new HashSet<>(visited);
        next.add(name);
        return resolveArg(bound.get(), ctx, next);
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
