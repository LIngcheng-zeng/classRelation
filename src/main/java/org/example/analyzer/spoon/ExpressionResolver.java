package org.example.analyzer.spoon;

import org.example.model.FieldRef;
import spoon.reflect.code.CtExpression;

import java.util.List;
import java.util.Set;

/**
 * Single-step strategy in an {@link ExpressionResolverChain}.
 *
 * Returns a non-empty list when this resolver can handle the expression.
 * Returns empty list to defer to the next resolver in the chain.
 *
 * Implementations must not mutate the {@code visited} set directly;
 * they should create a copy if recursion is needed.
 */
@FunctionalInterface
public interface ExpressionResolver {

    /**
     * @param expr    expression to resolve into source FieldRefs
     * @param ctx     execution context (scope bindings + lambda param types)
     * @param chain   the full chain, for recursive delegation
     * @param visited variable names already on the resolution stack (cycle guard)
     * @return resolved FieldRefs, or empty list to defer to next resolver
     */
    List<FieldRef> resolve(CtExpression<?> expr, ExecutionContext ctx,
                           ExpressionResolverChain chain, Set<String> visited);
}
