package org.example.util;

/**
 * Single step in a class-name resolution chain.
 *
 * Implementations should return {@code null} to signal "I cannot resolve this
 * context" and let the next step in the chain try.  A non-null return
 * short-circuits all subsequent steps.
 *
 * {@code C} is the resolution context type — typically either a Spoon
 * {@code CtExpression<?>} or a JavaParser scope context record.
 */
@FunctionalInterface
public interface ClassNameResolver<C> {
    String resolve(C context);
}
