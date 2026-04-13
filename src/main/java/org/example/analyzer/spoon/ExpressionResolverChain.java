package org.example.analyzer.spoon;

import org.example.model.FieldRef;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

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
        chain.add(ternaryResolver());
        chain.add(scopeBindingResolver());
        chain.add(variableTypeResolver(helper));
        chain.add(lambdaResolver());
        chain.add(methodRefResolver(helper));
        chain.add(switchExpressionResolver());
        chain.add(arrayResolver(helper));
        chain.add(containerFactoryResolver(helper));
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

            // Source 0: expression's effective type — respects cast annotations, e.g. (UserClass) rawVar
            try {
                CtTypeReference<?> effectiveType = vr.getType();
                if (effectiveType != null) {
                    String fqn = safeQualifiedName(effectiveType);
                    if (fqn != null && !helper.isSystemClass(fqn)) {
                        return List.of(new FieldRef(fqn, name));
                    }
                }
            } catch (Exception ignored) {}

            // Source 1: declared type from ExecutionContext (lambda params)
            Optional<CtTypeReference<?>> ctxType = ctx.declaredType(name);
            if (ctxType.isPresent()) {
                String fqn = safeQualifiedName(ctxType.get());
                if (fqn != null && !helper.isSystemClass(fqn)) {
                    return List.of(new FieldRef(fqn, name));
                }
            }

            // Source 2: variable's declared type (fallback for cases where effective type is erased)
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
     * Handles ternary expressions: {@code a ? b : c}
     * Both branches may flow into the target — returns union of then and else refs.
     */
    private static ExpressionResolver ternaryResolver() {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtConditional<?> cond)) return List.of();
            List<FieldRef> refs = new ArrayList<>();
            refs.addAll(chain.resolve(cond.getThenExpression(), ctx, visited));
            refs.addAll(chain.resolve(cond.getElseExpression(), ctx, visited));
            return refs;
        };
    }

    /**
     * Handles lambda expressions: {@code x -> x.getField()} or {@code x -> { return x.getField(); }}
     * Expression-body lambdas: recurse directly into the body expression.
     * Block-body lambdas: collect all CtReturn statements and union their expressions.
     */
    private static ExpressionResolver lambdaResolver() {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtLambda<?> lambda)) return List.of();
            try {
                // expression lambda: body is directly a CtExpression
                CtExpression<?> bodyExpr = lambda.getBody() instanceof CtExpression<?>
                        ? (CtExpression<?>) lambda.getBody() : null;
                if (bodyExpr != null) return chain.resolve(bodyExpr, ctx, visited);

                // block lambda: scan all return statements
                CtBlock<?> block = lambda.getBody() instanceof CtBlock<?>
                        ? (CtBlock<?>) lambda.getBody() : null;
                if (block == null) return List.of();
                List<FieldRef> refs = new ArrayList<>();
                for (CtReturn<?> ret : block.getElements(new TypeFilter<>(CtReturn.class))) {
                    CtExpression<?> returned = ret.getReturnedExpression();
                    if (returned != null) refs.addAll(chain.resolve(returned, ctx, visited));
                }
                return refs;
            } catch (Exception ignored) {
                return List.of();
            }
        };
    }

    /**
     * Handles method references: {@code SomeClass::getXxx} or {@code SomeClass::factory}
     * Getter-style (getXxx): returns FieldRef(declaringType, fieldName).
     * Other methods: returns FieldRef(declaringType, methodName) as a type-level reference.
     * System classes are filtered out.
     */
    private static ExpressionResolver methodRefResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtExecutableReferenceExpression<?, ?> ref)) return List.of();
            try {
                CtExecutableReference<?> exec = ref.getExecutable();
                if (exec == null) return List.of();
                CtTypeReference<?> declaring = exec.getDeclaringType();
                if (declaring == null) return List.of();
                String fqn = declaring.getQualifiedName();
                if (helper.isSystemClass(fqn)) return List.of();
                String methodName = exec.getSimpleName();
                // getter-style: SomeClass::getXxx → FieldRef(SomeClass, xxx)
                if (methodName.startsWith("get") && methodName.length() > 3
                        && Character.isUpperCase(methodName.charAt(3))) {
                    String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                    return List.of(new FieldRef(fqn, fieldName));
                }
                // other: type-level reference
                return List.of(new FieldRef(fqn, methodName));
            } catch (Exception ignored) {
                return List.of();
            }
        };
    }

    /**
     * Handles switch expressions (Java 14+): {@code switch (x) { case A -> val; case B -> val2; }}
     * Collects yield/arrow values from all case arms and returns their union.
     */
    private static ExpressionResolver switchExpressionResolver() {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtSwitchExpression<?, ?> sw)) return List.of();
            List<FieldRef> refs = new ArrayList<>();
            try {
                for (spoon.reflect.code.CtCase<?> arm : sw.getCases()) {
                    for (CtStatement stmt : arm.getStatements()) {
                        // arrow-style: CtYieldStatement
                        if (stmt instanceof CtYieldStatement yield) {
                            CtExpression<?> val = yield.getExpression();
                            if (val != null) refs.addAll(chain.resolve(val, ctx, visited));
                        // expression directly inside case
                        } else if (stmt instanceof CtExpression<?> stmtExpr) {
                            refs.addAll(chain.resolve(stmtExpr, ctx, visited));
                        // colon-style with explicit return
                        } else if (stmt instanceof CtReturn<?> ret) {
                            CtExpression<?> returned = ret.getReturnedExpression();
                            if (returned != null) refs.addAll(chain.resolve(returned, ctx, visited));
                        }
                    }
                }
            } catch (Exception ignored) {}
            return refs;
        };
    }

    /**
     * Handles array creation expressions.
     *
     * With elements — {@code new UserClass[]{e1, e2}}: union of all element expressions.
     * Empty allocation — {@code new UserClass[n]}: returns a type-level FieldRef
     *   with empty fieldName to record the type dependency.
     */
    private static ExpressionResolver arrayResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtNewArray<?> arr)) return List.of();
            try {
                CtTypeReference<?> typeRef = arr.getType();
                if (typeRef == null) return List.of();
                CtTypeReference<?> component = (typeRef instanceof CtArrayTypeReference<?> atr)
                        ? atr.getComponentType() : typeRef;
                if (component == null) return List.of();
                String componentFqn = component.getQualifiedName();
                if (helper.isSystemClass(componentFqn)) return List.of();

                List<CtExpression<?>> elements = arr.getElements();
                if (elements != null && !elements.isEmpty()) {
                    // initializer with elements: union
                    List<FieldRef> refs = new ArrayList<>();
                    for (CtExpression<?> el : elements) {
                        refs.addAll(chain.resolve(el, ctx, visited));
                    }
                    return refs;
                }
                // empty allocation: record type-level dependency
                return List.of(new FieldRef(componentFqn, ""));
            } catch (Exception ignored) {
                return List.of();
            }
        };
    }

    /**
     * Handles container factory / monadic calls where the return type contains a user-defined class.
     *
     * Covers:
     *   - Monadic: map / flatMap / filter / orElse / orElseGet / orElseThrow / ifPresent / ...
     *   - Collection factory: List.of / Arrays.asList / Set.of / Collections.singletonList / ...
     *   - Tuple / entry: Map.entry / Map.of / Pair.of / ...
     *   - Stream traversal: stream / entrySet / values / keySet
     *
     * Detection strategy: any CtInvocation whose return type satisfies containsUserType()
     * has its arguments and target recursed and unioned — no method-name whitelist required.
     */
    private static ExpressionResolver containerFactoryResolver(SpoonResolutionHelper helper) {
        return (expr, ctx, chain, visited) -> {
            if (!(expr instanceof CtInvocation<?> inv)) return List.of();
            try {
                CtTypeReference<?> returnType = inv.getType();
                if (!helper.containsUserType(returnType)) return List.of();

                List<FieldRef> refs = new ArrayList<>();
                for (CtExpression<?> arg : inv.getArguments()) {
                    refs.addAll(chain.resolve(arg, ctx, visited));
                }
                CtExpression<?> target = inv.getTarget();
                // avoid infinite recursion on static calls where target is a CtTypeAccess
                if (target != null && !(target instanceof CtTypeAccess<?>)) {
                    refs.addAll(chain.resolve(target, ctx, visited));
                }
                return refs;
            } catch (Exception ignored) {
                return List.of();
            }
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
