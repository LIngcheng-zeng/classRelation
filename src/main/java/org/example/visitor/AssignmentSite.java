package org.example.visitor;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;

import java.util.Map;

/**
 * Captures one field assignment site: target.field = value.
 * Only collected when the LHS is a field access expression.
 */
public record AssignmentSite(
        Expression target,                   // LHS: the field being written to (data sink)
        Expression value,                    // RHS: the source expression (data source)
        AssignExpr assignExpr,               // full AST node for location info
        String location,                     // "FileName.java:line"
        Map<String, Expression> aliasMap     // local variable alias map from enclosing method
) {}
