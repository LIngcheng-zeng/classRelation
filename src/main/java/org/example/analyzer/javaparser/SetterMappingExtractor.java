package org.example.analyzer.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.classifier.RelationshipClassifier;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects field mappings from setter call sites.
 *
 * Collected pattern: obj.setXxx(value)
 * Semantically equivalent to: obj.xxx = value (WRITE_ASSIGNMENT)
 */
class SetterMappingExtractor implements MappingExtractor {

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
                if (!isSetter(n)) return;

                String     rawName   = n.getNameAsString();
                String     fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);
                Expression receiver  = n.getScope().get();
                Expression value     = n.getArgument(0);

                Map<String, Expression> map = aliasMap != null ? aliasMap : Collections.emptyMap();
                String location = fileName + ":" + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                ExpressionSide sourceSide  = fieldRefExtractor.extract(value, map);
                String         sinkClass   = fieldRefExtractor.resolveClassNamePublic(receiver, map);
                ExpressionSide sinkSide    = new ExpressionSide(
                        List.of(new FieldRef(sinkClass, fieldName)), "direct");

                if (!isValidPair(sourceSide, sinkSide)) return;

                MappingType type    = classifier.classify(sourceSide, sinkSide);
                String      rawExpr = truncate(n.toString());
                result.add(new FieldMapping(sourceSide, sinkSide, type, MappingMode.WRITE_ASSIGNMENT, rawExpr, location));
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

        return result;
    }
}
