package org.example.visitor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.Map;

/**
 * Captures one setter call site: obj.setXxx(value).
 * Semantically equivalent to: obj.xxx = value (WRITE_ASSIGNMENT).
 */
public record SetterCallSite(
        MethodCallExpr call,                 // the full setter MethodCallExpr
        Expression receiverScope,            // obj — the object whose field is written
        Expression value,                    // the argument passed to the setter (data source)
        String fieldName,                    // derived field name: "setOrderId" → "orderId"
        String location,                     // "FileName.java:line"
        Map<String, Expression> aliasMap     // local variable alias map from enclosing method
) {}
