package org.example.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.analyzer.LocalAliasResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AST visitor that collects setter call sites: obj.setXxx(value).
 * Each site is treated as WRITE_ASSIGNMENT equivalent to obj.xxx = value.
 */
public class SetterCallVisitor {

    public List<SetterCallSite> visit(CompilationUnit cu, String fileName) {
        List<SetterCallSite> sites = new ArrayList<>();

        new VoidVisitorAdapter<Map<String, Expression>>() {
            @Override
            public void visit(MethodDeclaration n, Map<String, Expression> ignored) {
                Map<String, Expression> aliasMap = LocalAliasResolver.resolve(n);
                super.visit(n, aliasMap);
            }

            @Override
            public void visit(LambdaExpr n, Map<String, Expression> aliasMap) {
                Map<String, Expression> childMap = new HashMap<>(aliasMap);
                n.getParameters().forEach(p -> childMap.remove(p.getNameAsString()));
                super.visit(n, childMap);
            }

            @Override
            public void visit(MethodCallExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);

                if (!isSetter(n)) return;

                String rawName  = n.getNameAsString();
                String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);
                Expression receiver = n.getScope().get();
                Expression value    = n.getArgument(0);

                String location = fileName + ":"
                        + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                sites.add(new SetterCallSite(
                        n, receiver, value, fieldName, location,
                        aliasMap != null ? aliasMap : Collections.emptyMap()
                ));
            }

            private boolean isSetter(MethodCallExpr mc) {
                String name = mc.getNameAsString();
                return name.startsWith("set")
                        && name.length() > 3
                        && Character.isUpperCase(name.charAt(3))
                        && mc.getArguments().size() == 1
                        && mc.getScope().isPresent();
            }
        }.visit(cu, Collections.emptyMap());

        return sites;
    }
}
