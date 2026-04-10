package org.example.analyzer.javaparser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scans a method body and builds a local variable alias map.
 *
 * Collected patterns (intra-method, single-file):
 *   VariableDeclarator:  Type id = <expr>   → alias: "id" → expr
 *   AssignExpr (NameExpr LHS): id = <expr>  → alias update: "id" → expr
 *
 * The map is used by FieldRefExtractor to resolve bare NameExpr references
 * back to their originating field access expressions.
 *
 * Note: the map may contain circular entries (e.g. a = b; b = a).
 * FieldRefExtractor guards against this with a visited-set during expansion.
 */
class LocalAliasResolver {

    static Map<String, Expression> resolve(MethodDeclaration method) {
        Map<String, Expression> aliasMap = new LinkedHashMap<>();

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(VariableDeclarationExpr n, Void arg) {
                super.visit(n, arg);
                n.getVariables().forEach(decl ->
                        decl.getInitializer().ifPresent(init ->
                                aliasMap.put(decl.getNameAsString(), init)
                        )
                );
            }

            @Override
            public void visit(ExpressionStmt n, Void arg) {
                super.visit(n, arg);
                if (!(n.getExpression() instanceof AssignExpr assign)) return;
                if (assign.getOperator() != AssignExpr.Operator.ASSIGN) return;
                if (!(assign.getTarget() instanceof NameExpr nameExpr)) return;
                aliasMap.put(nameExpr.getNameAsString(), assign.getValue());
            }
        }.visit(method, null);

        return aliasMap;
    }
}
