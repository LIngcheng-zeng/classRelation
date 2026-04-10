package org.example.analyzer.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.classifier.RelationshipClassifier;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;
import org.example.model.MappingType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects field mappings from equals() call sites.
 *
 * Supported patterns:
 *   caller.equals(arg)          — instance equals
 *   Objects.equals(arg1, arg2)  — null-safe static equals
 */
class EqualsMappingExtractor implements MappingExtractor {

    @Override
    public List<FieldMapping> extract(CompilationUnit cu, String fileName,
                                      FieldRefExtractor fieldRefExtractor,
                                      RelationshipClassifier classifier) {
        List<FieldMapping> result = new ArrayList<>();

        new VoidVisitorAdapter<Map<String, Expression>>() {
            @Override
            public void visit(MethodDeclaration n, Map<String, Expression> ignored) {
                super.visit(n, LocalAliasResolver.resolve(n));
            }

            @Override
            public void visit(LambdaExpr n, Map<String, Expression> aliasMap) {
                Map<String, Expression> child = new HashMap<>(aliasMap != null ? aliasMap : Collections.emptyMap());
                n.getParameters().forEach(p -> child.remove(p.getNameAsString()));
                super.visit(n, child);
            }

            @Override
            public void visit(MethodCallExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);
                if (!"equals".equals(n.getNameAsString())) return;

                Map<String, Expression> map = aliasMap != null ? aliasMap : Collections.emptyMap();
                String location = fileName + ":" + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                Expression caller;
                Expression argument;
                if (n.getArguments().size() == 1 && n.getScope().isPresent()) {
                    caller   = n.getScope().get();
                    argument = n.getArgument(0);
                } else if (n.getArguments().size() == 2 && isObjectsScope(n)) {
                    caller   = n.getArgument(0);
                    argument = n.getArgument(1);
                } else {
                    return;
                }

                ExpressionSide leftSide  = fieldRefExtractor.extract(caller,   map);
                ExpressionSide rightSide = fieldRefExtractor.extract(argument, map);
                if (!isValidPair(leftSide, rightSide)) return;

                MappingType type   = classifier.classify(leftSide, rightSide);
                String      rawExpr = truncate(n.toString());
                
                // GAP-03: Extract normalization operations from both sides
                List<String> leftNorm = fieldRefExtractor.extractNormalization(caller);
                List<String> rightNorm = fieldRefExtractor.extractNormalization(argument);
                List<String> allNorm = new ArrayList<>();
                allNorm.addAll(leftNorm);
                allNorm.addAll(rightNorm);
                
                result.add(new FieldMapping(leftSide, rightSide, type, MappingMode.READ_PREDICATE, rawExpr, location, allNorm));
            }

            private boolean isObjectsScope(MethodCallExpr n) {
                return n.getScope()
                        .filter(s -> s instanceof NameExpr ne
                                && ("Objects".equals(ne.getNameAsString())
                                    || "java.util.Objects".equals(ne.getNameAsString())))
                        .isPresent();
            }
        }.visit(cu, Collections.emptyMap());

        return result;
    }
}
