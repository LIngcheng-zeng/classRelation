package org.example.analyzer.spoon;

import org.example.model.FieldRef;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ordered chain of {@link ExpressionResolver} strategies.
 *
 * Resolvers are tried in order; the first non-empty result wins.
 * This replaces the ad-hoc {@code collectFieldRefs} recursion in SpoonResolutionHelper
 * with a clean open/closed strategy pattern.
 *
 * Standard chain (built via {@link #standard}):
 *   1. FieldReadResolver      — CtFieldRead → declaring type + field name
 *   2. GetterResolver         — getXxx() invocation → receiver type + field name
 *   3. ScopeBindingResolver   — CtVariableRead in ctx bindings → recurse on bound expr
 *   4. VariableTypeResolver   — CtVariableRead with known type (lambda params, Spoon fallback)
 *   5. MonadicContainerResolver — map/flatMap/filter etc. → recurse into args
 */
public final class ExpressionResolverChain {

    private final List<ExpressionResolver> resolvers;

    private ExpressionResolverChain(List<ExpressionResolver> resolvers) {
        this.resolvers = Collections.unmodifiableList(resolvers);
    }

    /**
     * Builds the standard resolver chain using the given helper for predicates
     * and class-name resolution.
     */
    public static ExpressionResolverChain standard(SpoonResolutionHelper helper) {
        List<ExpressionResolver> chain = new ArrayList<>();
        chain.add(fieldReadResolver(helper));
        chain.add(getterResolver(helper));
        chain.add(scopeBindingResolver());
        chain.add(variableTypeResolver(helper));
        chain.add(monadicContainerResolver());
        return new ExpressionResolverChain(chain);
    }

    // ── public entry points ──────────────────────────────────────────────────

    /** Resolve with a fresh visited set. */
    public List<FieldRef> resolve(CtExpression<?> expr, ExecutionContext ctx) {
        return resolve(expr, ctx, new HashSet<>());
    }

    /** Resolve with an existing visited set (for recursive calls). */
    public List<FieldRef> resolve(CtExpression<?> expr, ExecutionContext ctx, Set<String> visited) {
        if (expr == null) return List.of();
        for (ExpressionResolver r : resolvers) {
            List<FieldRef> result = r.resolve(expr, ctx, this, visited);
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    // ── resolver factories ───────────────────────────────────────────────────

    /**
     * Handles direct field reads: {@code obj.field}
     * Returns the declaring type + field name.
     */
    private static ExpressionResolver fieldReadResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtFieldRead<?> fr)) return List.of();
            return List.of(new FieldRef(
                    helper.resolveClassNameFromFieldRef(fr),
                    fr.getVariable().getSimpleName()));
        };
    }

    /**
     * Handles Lombok / Java getter calls: {@code obj.getXxx()}
     * Strips the "get" prefix and lowercases the first letter as the field name.
     */
    private static ExpressionResolver getterResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtInvocation<?> inv) || !helper.isGetter(inv)) return List.of();
            String raw       = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            return List.of(new FieldRef(helper.resolveClassName(inv.getTarget()), fieldName));
        };
    }

    /**
     * Handles variable reads that exist in the current scope bindings.
     * Follows the alias chain (cycle-guarded via visited set).
     *
     * Example: {@code order} → bound to {@code orderDTO.getOrder()} → recurses.
     */
    private static ExpressionResolver scopeBindingResolver() {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtVariableRead<?> vr)) return List.of();
            String name = vr.getVariable().getSimpleName();
            if (visited.contains(name)) return List.of();

            Optional<CtExpression<?>> bound = ctx.binding(name);
            if (bound.isEmpty()) return List.of();

            Set<String> next = new HashSet<>(visited);
            next.add(name);
            return chain.resolve(bound.get(), ctx, next);
        };
    }

    /**
     * Handles variable reads where the declared type is known.
     *
     * Two sources of type information (tried in order):
     *   1. {@code ctx.declaredType(name)} — lambda parameter type from ExecutionContext
     *   2. {@code vr.getVariable().getType()} — Spoon's own type inference (universal fallback)
     *
     * This handles lambda parameters that are not in scope bindings:
     * {@code item -> ItemDetail.builder().item(item)} — {@code item} has type {@code Item}
     * even though it is not bound to any expression in the alias map.
     */
    private static ExpressionResolver variableTypeResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtVariableRead<?> vr)) return List.of();
            String name = vr.getVariable().getSimpleName();

            // Source 1: declared type from ExecutionContext (lambda params)
            Optional<CtTypeReference<?>> ctxType = ctx.declaredType(name);
            if (ctxType.isPresent()) {
                String fqn = safeQualifiedName(ctxType.get());
                if (fqn != null && !helper.isSystemClass(fqn)) {
                    return List.of(new FieldRef(fqn, name));
                }
            }

            // Source 2: Spoon's own type inference (works for any variable, including lambda params)
            try {
                CtTypeReference<?> type = vr.getVariable().getType();
                if (type != null) {
                    String fqn = safeQualifiedName(type);
                    if (fqn != null && !helper.isSystemClass(fqn)) {
                        return List.of(new FieldRef(fqn, name));
                    }
                }
            } catch (Exception ignored) {}

            return List.of();
        };
    }

    /**
     * Handles monadic container calls: {@code stream.map(f)}, {@code optional.orElse(x)}, etc.
     * Recurses into the lambda/method-ref argument and the target stream/optional.
     */
    private static ExpressionResolver monadicContainerResolver() {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtInvocation<?> inv)) return List.of();
            String name = inv.getExecutable().getSimpleName();
            boolean isMonadic = name.equals("map")        || name.equals("flatMap")
                    || name.equals("filter")  || name.equals("orElse")
                    || name.equals("orElseGet") || name.equals("orElseThrow")
                    || name.equals("ifPresent") || name.equals("ifPresentOrElse")
                    || name.equals("stream")  || name.equals("entrySet")
                    || name.equals("values")  || name.equals("keySet");
            if (!isMonadic) return List.of();

            List<FieldRef> refs = new ArrayList<>();
            for (CtExpression<?> arg : inv.getArguments()) {
                refs.addAll(chain.resolve(arg, ctx, visited));
            }
            CtExpression<?> target = inv.getTarget();
            if (target != null) refs.addAll(chain.resolve(target, ctx, visited));
            return refs;
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String safeQualifiedName(CtTypeReference<?> ref) {
        try {
            return ref.getQualifiedName();
        } catch (Exception ignored) {
            return null;
        }
    }
}
