package org.example.analyzer.javaparser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.example.classifier.RelationshipClassifier;
import org.example.model.FieldMapping;
import org.example.resolution.SymbolResolutionResult;
import org.example.spi.SourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaParser-based implementation of {@link SourceAnalyzer}.
 *
 * Orchestrates a configurable list of {@link MappingExtractor}s over every
 * .java file found under the project root.
 *
 * Raw mappings are returned without FQN qualification; {@link org.example.resolution.FieldRefQualifier}
 * normalises all class names centrally after all extractors have run.
 *
 * To add a new JavaParser detection pattern (e.g. constructor calls):
 *   1. Implement {@link MappingExtractor}
 *   2. Add an instance to the {@code extractors} list in the constructor
 */
public class JavaParserAnalyzer implements SourceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaParserAnalyzer.class);

    private final FieldRefExtractor      extractor  = new FieldRefExtractor();
    private final RelationshipClassifier classifier = new RelationshipClassifier();

    private final List<MappingExtractor> extractors = List.of(
            new EqualsMappingExtractor(),
            new AssignmentMappingExtractor(),
            new SetterMappingExtractor()
    );

    @Override
    public List<FieldMapping> analyze(Path projectRoot, SymbolResolutionResult symbols) {
        configureSymbolSolver(projectRoot);

        List<FieldMapping> mappings = new ArrayList<>();

        for (Path file : symbols.javaFiles()) {
            try {
                CompilationUnit cu       = StaticJavaParser.parse(file);
                String          fileName = file.getFileName().toString();
                for (MappingExtractor me : extractors) {
                    mappings.addAll(me.extract(cu, fileName, extractor, classifier));
                }
            } catch (IOException e) {
                log.warn("Failed to parse file: {} — {}", file, e.getMessage());
            } catch (Exception e) {
                log.warn("Unexpected error parsing: {} — {}", file, e.getMessage());
            }
        }

        return mappings;
    }

    // -------------------------------------------------------------------------

    private void configureSymbolSolver(Path projectRoot) {
        try {
            CombinedTypeSolver solver = new CombinedTypeSolver();
            solver.add(new ReflectionTypeSolver());
            solver.add(new JavaParserTypeSolver(projectRoot));

            StaticJavaParser.setConfiguration(
                    new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(solver)));
        } catch (Exception e) {
            log.warn("Symbol solver setup failed, type resolution will be degraded: {}", e.getMessage());
            StaticJavaParser.setConfiguration(new ParserConfiguration());
        }
    }
}
