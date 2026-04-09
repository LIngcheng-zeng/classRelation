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
import org.example.model.MappingType;
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

    private final JavaFileScanner    scanner    = new JavaFileScanner();
    private final EqualsCallVisitor  visitor    = new EqualsCallVisitor();
    private final FieldRefExtractor  extractor  = new FieldRefExtractor();
    private final RelationshipClassifier classifier = new RelationshipClassifier();

    public List<ClassRelation> analyze(Path projectRoot) {
        configureSymbolSolver(projectRoot);

        List<Path> javaFiles = scanner.scan(projectRoot);
        LineageGraph graph = new LineageGraph();

        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                String fileName = file.getFileName().toString();

                List<EqualCallSite> sites = visitor.visit(cu, fileName);
                for (EqualCallSite site : sites) {
                    processCallSite(site, graph);
                }
            } catch (IOException e) {
                log.warning("Failed to parse file: " + file + " — " + e.getMessage());
            } catch (Exception e) {
                log.warning("Unexpected error parsing: " + file + " — " + e.getMessage());
            }
        }

        return graph.buildRelations();
    }

    private void processCallSite(EqualCallSite site, LineageGraph graph) {
        ExpressionSide leftSide  = extractor.extract(site.caller());
        ExpressionSide rightSide = extractor.extract(site.argument());

        // Skip if neither side references any class fields
        if (leftSide.isEmpty() && rightSide.isEmpty()) return;
        // Skip if classes cannot be resolved on both sides
        boolean leftHasClass  = leftSide.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = rightSide.fields().stream().anyMatch(f -> f.className() != null);
        if (!leftHasClass || !rightHasClass) return;

        MappingType type = classifier.classify(leftSide, rightSide);
        // Truncate raw expression for readability
        String rawExpr = site.callExpr().toString();
        if (rawExpr.length() > 120) rawExpr = rawExpr.substring(0, 117) + "...";

        FieldMapping mapping = new FieldMapping(leftSide, rightSide, type, rawExpr, site.location());
        graph.addMapping(mapping);
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
