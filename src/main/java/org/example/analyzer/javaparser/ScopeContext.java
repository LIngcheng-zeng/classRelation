package org.example.analyzer.javaparser;

import com.github.javaparser.ast.expr.Expression;

import java.util.Map;
import java.util.Set;

/**
 * Immutable context passed through the JavaParser scope-resolution chain.
 *
 * Carries the three pieces of state that every resolver step needs:
 *   scope     — the expression whose declaring class is being resolved
 *   aliasMap  — local variable → originating expression (from SpoonAliasBuilder equivalent)
 *   visited   — cycle guard for alias expansion
 */
record ScopeContext(Expression scope,
                    Map<String, Expression> aliasMap,
                    Set<String> visited) {

    /** Convenience constructor for a single-scope lookup without alias context. */
    static ScopeContext of(Expression scope,
                           Map<String, Expression> aliasMap,
                           Set<String> visited) {
        return new ScopeContext(scope, aliasMap, visited);
    }
}
