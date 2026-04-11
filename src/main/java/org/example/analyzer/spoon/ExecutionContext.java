package org.example.analyzer.spoon;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable execution context for one scope level (method or lambda body).
 *
 * Replaces the raw {@code Map<String, CtExpression<?>>} aliasMap used previously.
 * Supports scope nesting via {@link #enterLambda} and {@link #enterCallee},
 * enabling correct resolution of lambda parameters and captured outer variables.
 *
 * Scope chain:
 *   forMethod(m) → enterLambda(outer) → enterLambda(inner) → …
 *
 * Resolution walks up the chain, so inner scopes shadow outer ones.
 */
public final class ExecutionContext {

    private final ExecutionContext              parent;
    private final Map<String, CtExpression<?>> bindings;   // var name → initializer expr
    private final Map<String, CtTypeReference<?>> paramTypes; // lambda/callee param → declared type

    private ExecutionContext(ExecutionContext parent,
                             Map<String, CtExpression<?>> bindings,
                             Map<String, CtTypeReference<?>> paramTypes) {
        this.parent     = parent;
        this.bindings   = Collections.unmodifiableMap(bindings);
        this.paramTypes = Collections.unmodifiableMap(paramTypes);
    }

    // ── resolution ───────────────────────────────────────────────────────────

    /** Resolve a variable name to its initializer expression, walking up the scope chain. */
    public Optional<CtExpression<?>> binding(String name) {
        if (bindings.containsKey(name)) return Optional.of(bindings.get(name));
        return parent != null ? parent.binding(name) : Optional.empty();
    }

    /** Resolve a parameter name to its declared type (lambda / callee params), walking up. */
    public Optional<CtTypeReference<?>> declaredType(String name) {
        if (paramTypes.containsKey(name)) return Optional.of(paramTypes.get(name));
        return parent != null ? parent.declaredType(name) : Optional.empty();
    }

    /** All bindings visible in this scope (for extractors that iterate over values). */
    public Map<String, CtExpression<?>> bindings() { return bindings; }

    // ── scope entry ──────────────────────────────────────────────────────────

    /**
     * Creates a child context for a lambda body.
     * Lambda parameters are added to paramTypes; local variable declarations
     * in the lambda body are added to bindings. Parent scope is inherited.
     */
    public ExecutionContext enterLambda(CtLambda<?> lambda) {
        Map<String, CtTypeReference<?>> params = new LinkedHashMap<>();
        for (CtParameter<?> p : lambda.getParameters()) {
            try {
                CtTypeReference<?> type = p.getType();
                if (type != null) params.put(p.getSimpleName(), type);
            } catch (Exception ignored) {}
        }
        return new ExecutionContext(this, buildLocalBindings(lambda), params);
    }

    /**
     * Creates a child context for a callee (inter-procedural call).
     * Call-site arguments are projected onto callee parameter names.
     * The resulting context has no parent (projected args are self-contained).
     */
    public ExecutionContext enterCallee(CtExecutable<?> callee, List<CtExpression<?>> callArgs) {
        Map<String, CtExpression<?>> projected = new LinkedHashMap<>();
        List<CtParameter<?>> params = callee.getParameters();
        int limit = Math.min(params.size(), callArgs.size());
        for (int i = 0; i < limit; i++) {
            projected.put(params.get(i).getSimpleName(), callArgs.get(i));
        }
        return new ExecutionContext(null, projected, Map.of());
    }

    // ── factories ────────────────────────────────────────────────────────────

    /** Creates a top-level context from a method's local variable declarations. */
    public static ExecutionContext forMethod(CtExecutable<?> method) {
        return new ExecutionContext(null, buildLocalBindings(method), Map.of());
    }

    /**
     * Builds the full context chain from method level down to the given lambda,
     * correctly threading any intermediate enclosing lambdas.
     *
     * Example for {@code outer -> inner -> build()}:
     *   forLambda(innerLambda, methodCtx)
     *   → methodCtx.enterLambda(outer) → .enterLambda(inner)
     */
    public static ExecutionContext forLambda(CtLambda<?> lambda, ExecutionContext methodCtx) {
        Deque<CtLambda<?>> chain = new ArrayDeque<>();
        CtElement current = lambda;
        while (current != null) {
            if (current instanceof CtLambda<?> l) chain.push(l);
            try {
                CtElement parent = current.getParent();
                // Stop when we reach the enclosing method (non-lambda executable)
                if (parent instanceof CtExecutable<?> && !(parent instanceof CtLambda<?>)) break;
                current = parent;
            } catch (Exception e) { break; }
        }
        // chain is [innermost, ..., outermost]; reverse to build from outermost in
        List<CtLambda<?>> ordered = new ArrayList<>(chain);
        Collections.reverse(ordered);
        ExecutionContext ctx = methodCtx;
        for (CtLambda<?> l : ordered) ctx = ctx.enterLambda(l);
        return ctx;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static Map<String, CtExpression<?>> buildLocalBindings(CtElement scope) {
        Map<String, CtExpression<?>> map = new LinkedHashMap<>();
        try {
            for (CtLocalVariable<?> lv : scope.getElements(new TypeFilter<>(CtLocalVariable.class))) {
                if (lv.getDefaultExpression() != null)
                    map.put(lv.getSimpleName(), lv.getDefaultExpression());
            }
            for (CtAssignment<?, ?> assign : scope.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (assign.getAssigned() instanceof CtVariableWrite<?> vw)
                    map.put(vw.getVariable().getSimpleName(), assign.getAssignment());
            }
        } catch (Exception ignored) {}
        return map;
    }
}
