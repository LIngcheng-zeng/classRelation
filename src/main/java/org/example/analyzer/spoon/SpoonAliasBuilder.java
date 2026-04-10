package org.example.analyzer.spoon;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a local variable alias map from a Spoon executable (method or constructor).
 *
 * Collected patterns:
 *   CtLocalVariable:  Type id = expr  → alias: "id" → expr
 *   CtAssignment (simple var LHS): id = expr → alias update: "id" → expr
 *
 * Spoon counterpart of LocalAliasResolver in the javaparser package.
 * Note: circular aliases are possible (a = b; b = a).
 * CallProjectionExtractor guards against this with a visited-set.
 */
class SpoonAliasBuilder {

    static Map<String, CtExpression<?>> build(CtExecutable<?> method) {
        Map<String, CtExpression<?>> aliasMap = new LinkedHashMap<>();

        // Local variable declarations: Type id = expr
        for (CtLocalVariable<?> lv : method.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (lv.getDefaultExpression() != null) {
                aliasMap.put(lv.getSimpleName(), lv.getDefaultExpression());
            }
        }

        // Simple reassignments: id = expr  (NameWrite on LHS)
        for (CtAssignment<?, ?> assign : method.getElements(new TypeFilter<>(CtAssignment.class))) {
            String lhsName = extractSimpleName(assign.getAssigned());
            if (lhsName != null) {
                aliasMap.put(lhsName, assign.getAssignment());
            }
        }

        return aliasMap;
    }

    /** Returns the simple variable name if the expression is a plain variable write, else null. */
    private static String extractSimpleName(CtExpression<?> expr) {
        // CtVariableWrite wraps simple variable names on LHS of assignment
        if (expr instanceof spoon.reflect.code.CtVariableWrite<?> vw) {
            return vw.getVariable().getSimpleName();
        }
        return null;
    }
}
