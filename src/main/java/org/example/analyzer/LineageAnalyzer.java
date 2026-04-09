package org.example.analyzer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.example.classifier.RelationshipClassifier;
import org.example.graph.LineageGraph;
import org.example.model.ClassRelation;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import org.example.visitor.AssignmentSite;
import org.example.visitor.AssignmentVisitor;
import org.example.visitor.EqualCallSite;
import org.example.visitor.EqualsCallVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Orchestrates the full analysis pipeline:
 *   scan → parse → visit → extract → classify → aggregate
 */
public class LineageAnalyzer {

    private static final Logger log = Logger.getLogger(LineageAnalyzer.class.getName());

    private final JavaFileScanner        scanner         = new JavaFileScanner();
    private final EqualsCallVisitor      equalsVisitor   = new EqualsCallVisitor();
    private final AssignmentVisitor      assignVisitor   = new AssignmentVisitor();
    private final FieldRefExtractor      extractor       = new FieldRefExtractor();
    private final RelationshipClassifier classifier      = new RelationshipClassifier();

    public List<ClassRelation> analyze(Path projectRoot) {
        configureSymbolSolver(projectRoot);

        List<Path> javaFiles = scanner.scan(projectRoot);
        LineageGraph graph = new LineageGraph();

        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                String fileName = file.getFileName().toString();

                List<EqualCallSite> equalSites = equalsVisitor.visit(cu, fileName);
                for (EqualCallSite site : equalSites) {
                    processEqualsSite(site, graph);
                }

                List<AssignmentSite> assignSites = assignVisitor.visit(cu, fileName);
                for (AssignmentSite site : assignSites) {
                    processAssignmentSite(site, graph);
                }
            } catch (IOException e) {
                log.warning("Failed to parse file: " + file + " — " + e.getMessage());
            } catch (Exception e) {
                log.warning("Unexpected error parsing: " + file + " — " + e.getMessage());
            }
        }

        return graph.buildRelations();
    }

    private void processEqualsSite(EqualCallSite site, LineageGraph graph) {
        ExpressionSide leftSide  = extractor.extract(site.caller(),   site.aliasMap());
        ExpressionSide rightSide = extractor.extract(site.argument(), site.aliasMap());

        if (!isValidPair(leftSide, rightSide)) return;

        MappingType type = classifier.classify(leftSide, rightSide);
        String rawExpr = truncate(site.callExpr().toString());

        graph.addMapping(new FieldMapping(leftSide, rightSide, type, MappingMode.READ_PREDICATE, rawExpr, site.location()));
    }

    private void processAssignmentSite(AssignmentSite site, LineageGraph graph) {
        // Convention: leftSide = data source (RHS value), rightSide = data sink (LHS target)
        ExpressionSide sourceSide = extractor.extract(site.value(),  site.aliasMap());
        ExpressionSide sinkSide   = extractor.extract(site.target(), site.aliasMap());

        if (!isValidPair(sourceSide, sinkSide)) return;

        MappingType type = classifier.classify(sourceSide, sinkSide);
        String rawExpr = truncate(site.assignExpr().toString());

        graph.addMapping(new FieldMapping(sourceSide, sinkSide, type, MappingMode.WRITE_ASSIGNMENT, rawExpr, site.location()));
    }

    /**
     * At least one side must have a resolved className, and not both sides empty.
     */
    private boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }

    private String truncate(String expr) {
        return expr.length() > 120 ? expr.substring(0, 117) + "..." : expr;
    }

    private void configureSymbolSolver(Path projectRoot) {
        try {
            CombinedTypeSolver solver = new CombinedTypeSolver();
            solver.add(new ReflectionTypeSolver());
            solver.add(new JavaParserTypeSolver(projectRoot));

            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(symbolSolver);
            StaticJavaParser.setConfiguration(config);
        } catch (Exception e) {
            log.warning("Symbol solver setup failed, type resolution will be degraded: " + e.getMessage());
            // Fallback: parse without symbol resolution
            StaticJavaParser.setConfiguration(new ParserConfiguration());
        }
    }

}
