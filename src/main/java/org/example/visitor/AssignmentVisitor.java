package org.example.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.analyzer.LocalAliasResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AST visitor that collects field assignment sites within a CompilationUnit.
 * Attaches the enclosing method's local alias map to each site.
 *
 * Collected pattern: obj.field = expr
 * Skipped patterns:
 *   - bare NameExpr on LHS (local variable, owning class unresolvable)
 *   - compound assignments (+=, -=, etc.)
 */
public class AssignmentVisitor {

    public List<AssignmentSite> visit(CompilationUnit cu, String fileName) {
        List<AssignmentSite> sites = new ArrayList<>();

        new VoidVisitorAdapter<Map<String, Expression>>() {
            @Override
            public void visit(MethodDeclaration n, Map<String, Expression> ignored) {
                Map<String, Expression> aliasMap = LocalAliasResolver.resolve(n);
                super.visit(n, aliasMap);
            }

            @Override
            public void visit(AssignExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);

                if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
                if (!(n.getTarget() instanceof FieldAccessExpr)) return;

                String location = fileName + ":"
                        + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                sites.add(new AssignmentSite(
                        n.getTarget(),
                        n.getValue(),
                        n,
                        location,
                        aliasMap != null ? aliasMap : Collections.emptyMap()
                ));
            }
        }.visit(cu, Collections.emptyMap());

        return sites;
    }
}
