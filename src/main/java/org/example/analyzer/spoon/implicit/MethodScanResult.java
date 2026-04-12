package org.example.analyzer.spoon.implicit;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.CtScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of all AST nodes relevant to implicit-equality detection,
 * collected in a SINGLE traversal of the method body via {@link CtScanner}.
 *
 * Replaces the pattern of each {@link MapFactCollector}/{@link BridgeDetector}
 * calling {@code method.getElements(new TypeFilter<>(...)) } independently —
 * which previously caused 6–8 separate traversals per method.
 *
 * Usage:
 *   MethodScanResult scan = MethodScanResult.of(method);
 *   // all patterns share the same pre-collected lists
 */
public final class MethodScanResult {

    public final List<CtLocalVariable<?>>  localVars;
    public final List<CtInvocation<?>>     invocations;
    public final List<CtAssignment<?, ?>>  assignments;

    private MethodScanResult(List<CtLocalVariable<?>>  localVars,
                              List<CtInvocation<?>>     invocations,
                              List<CtAssignment<?, ?>>  assignments) {
        this.localVars   = Collections.unmodifiableList(localVars);
        this.invocations = Collections.unmodifiableList(invocations);
        this.assignments = Collections.unmodifiableList(assignments);
    }

    /**
     * Scans {@code method} exactly once, bucketing nodes by type.
     * The scanner recurses into lambdas and nested blocks so the result
     * mirrors what {@code getElements(new TypeFilter<>(...)) } would return.
     */
    public static MethodScanResult of(CtExecutable<?> method) {
        List<CtLocalVariable<?>>  localVars   = new ArrayList<>();
        List<CtInvocation<?>>     invocations = new ArrayList<>();
        List<CtAssignment<?, ?>>  assignments = new ArrayList<>();

        method.accept(new CtScanner() {
            @Override
            public <T> void visitCtLocalVariable(CtLocalVariable<T> v) {
                localVars.add(v);
                super.visitCtLocalVariable(v);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> void visitCtInvocation(CtInvocation<T> inv) {
                invocations.add((CtInvocation<?>) inv);
                super.visitCtInvocation(inv);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T, A extends T> void visitCtAssignment(CtAssignment<T, A> assign) {
                assignments.add((CtAssignment<?, ?>) assign);
                super.visitCtAssignment(assign);
            }
        });

        return new MethodScanResult(localVars, invocations, assignments);
    }
}
