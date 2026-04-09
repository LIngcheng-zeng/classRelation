package org.example.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.analyzer.LocalAliasResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AST visitor that collects all .equals() call sites within a CompilationUnit.
 * Attaches the enclosing method's local alias map to each site.
 */
public class EqualsCallVisitor {

    public List<EqualCallSite> visit(CompilationUnit cu, String fileName) {
        List<EqualCallSite> sites = new ArrayList<>();

        new VoidVisitorAdapter<Map<String, Expression>>() {
            @Override
            public void visit(MethodDeclaration n, Map<String, Expression> ignored) {
                // Build alias map for this method, then visit its body
                Map<String, Expression> aliasMap = LocalAliasResolver.resolve(n);
                super.visit(n, aliasMap);
            }

            @Override
            public void visit(MethodCallExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);

                if (!"equals".equals(n.getNameAsString())) return;

                Map<String, Expression> map = aliasMap != null ? aliasMap : Collections.emptyMap();
                String location = fileName + ":"
                        + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                if (n.getArguments().size() == 1 && n.getScope().isPresent()) {
                    // Pattern: caller.equals(arg)
                    sites.add(new EqualCallSite(
                            n.getScope().get(), n.getArgument(0), n, location, map));

                } else if (n.getArguments().size() == 2 && isObjectsScope(n)) {
                    // Pattern: Objects.equals(a, b)
                    sites.add(new EqualCallSite(
                            n.getArgument(0), n.getArgument(1), n, location, map));
                }
            }

            private boolean isObjectsScope(MethodCallExpr n) {
                return n.getScope()
                        .filter(s -> s instanceof NameExpr ne
                                && ("Objects".equals(ne.getNameAsString())
                                    || "java.util.Objects".equals(ne.getNameAsString())))
                        .isPresent();
            }
        }.visit(cu, Collections.emptyMap());

        return sites;
    }
}
