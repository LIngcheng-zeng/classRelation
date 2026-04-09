package org.example.visitor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

/**
 * Captures one equals() call site: caller.equals(argument).
 */
public record EqualCallSite(
        Expression caller,       // left side: the object calling equals()
        Expression argument,     // right side: the argument passed to equals()
        MethodCallExpr callExpr, // the full MethodCallExpr node for location info
        String location          // "FileName.java:line"
) {}
