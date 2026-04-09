package org.example.visitor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.Map;

/**
 * Captures one equals() call site: caller.equals(argument).
 */
public record EqualCallSite(
        Expression caller,                    // left side: the object calling equals()
        Expression argument,                  // right side: the argument passed to equals()
        MethodCallExpr callExpr,              // the full MethodCallExpr node for location info
        String location,                      // "FileName.java:line"
        Map<String, Expression> aliasMap      // local variable alias map from enclosing method
) {}
