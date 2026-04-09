package org.example.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * AST visitor that collects all .equals() call sites within a CompilationUnit.
 * Handles both: caller.equals(arg) patterns.
 */
public class EqualsCallVisitor {

    public List<EqualCallSite> visit(CompilationUnit cu, String fileName) {
        List<EqualCallSite> sites = new ArrayList<>();

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);

                if (!"equals".equals(n.getNameAsString())) return;
                // Must have exactly one argument
                if (n.getArguments().size() != 1) return;
                // Must have a scope (caller): caller.equals(arg)
                if (n.getScope().isEmpty()) return;

                String location = fileName + ":"
                        + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                sites.add(new EqualCallSite(
                        n.getScope().get(),
                        n.getArgument(0),
                        n,
                        location
                ));
            }
        }.visit(cu, null);

        return sites;
    }
}
