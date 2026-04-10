package org.example.analyzer.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
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
 * Detects field mappings from direct field assignment sites.
 *
 * Collected pattern: obj.field = expr
 * Skipped patterns:
 *   - bare NameExpr on LHS (local variable, owning class unresolvable)
 *   - compound assignments (+=, -=, etc.)
 */
class AssignmentMappingExtractor implements MappingExtractor {

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
            public void visit(AssignExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);
                if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
                if (!(n.getTarget() instanceof FieldAccessExpr)) return;

                Map<String, Expression> map = aliasMap != null ? aliasMap : Collections.emptyMap();
                String location = fileName + ":" + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                // Convention: sourceSide = data source (RHS), sinkSide = data sink (LHS)
                ExpressionSide sourceSide = fieldRefExtractor.extract(n.getValue(),  map);
                ExpressionSide sinkSide   = fieldRefExtractor.extract(n.getTarget(), map);
                if (!isValidPair(sourceSide, sinkSide)) return;

                MappingType type    = classifier.classify(sourceSide, sinkSide);
                String      rawExpr = truncate(n.toString());
                result.add(new FieldMapping(sourceSide, sinkSide, type, MappingMode.WRITE_ASSIGNMENT, rawExpr, location));
            }
        }.visit(cu, Collections.emptyMap());

        return result;
    }
}
