package org.example.analyzer.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.example.analyzer.javaparser.intra.AssignmentMappingExtractor;
import org.example.analyzer.javaparser.intra.EqualsMappingExtractor;
import org.example.analyzer.javaparser.intra.SetterMappingExtractor;
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

    private final RelationshipClassifier classifier = new RelationshipClassifier();

    private final List<MappingExtractor> extractors = List.of(
            new EqualsMappingExtractor(),
            new AssignmentMappingExtractor(),
            new SetterMappingExtractor()
    );

    @Override
    public List<FieldMapping> analyze(Path projectRoot, SymbolResolutionResult symbols) {
        long ts = System.currentTimeMillis();
        // Instance-level JavaParser — eliminates global static state written by
        // StaticJavaParser.setConfiguration(), enabling safe concurrent use.
        JavaParser parser = buildParser(projectRoot);
        log.info("[PERF] JavaParserAnalyzer.buildParser: {}ms", System.currentTimeMillis() - ts);

        // Create FieldRefExtractor with Spoon model + classPackageIndex for hybrid type inference
        FieldRefExtractor extractor = new FieldRefExtractor(symbols.spoonModel(), symbols.classPackageIndex());

        List<FieldMapping> mappings = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        int fileCount = 0;

        for (Path file : symbols.javaFiles()) {
            fileCount++;
            long fileStart = System.currentTimeMillis();
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (!result.isSuccessful() || result.getResult().isEmpty()) continue;
                CompilationUnit cu       = result.getResult().get();
                String          fileName = file.getFileName().toString();
                for (MappingExtractor me : extractors) {
                    mappings.addAll(me.extract(cu, fileName, extractor, classifier));
                }
            } catch (IOException e) {
                log.warn("Failed to parse file: {} — {}", file, e.getMessage());
            } catch (Exception e) {
                log.warn("Unexpected error parsing: {} — {}", file, e.getMessage());
            }
            long fileElapsed = System.currentTimeMillis() - fileStart;
            if (fileElapsed > 2000) {
                log.warn("[PERF] JavaParserAnalyzer slow file: {} — {}ms", file.getFileName(), fileElapsed);
            }
        }

        log.info("[PERF] JavaParserAnalyzer: {}ms  files={}  mappings={}",
                System.currentTimeMillis() - t0, fileCount, mappings.size());
        return mappings;
    }

    // -------------------------------------------------------------------------

    private JavaParser buildParser(Path projectRoot) {
        try {
            CombinedTypeSolver solver = new CombinedTypeSolver();
            solver.add(new ReflectionTypeSolver());
            solver.add(new JavaParserTypeSolver(projectRoot));
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(solver));
            return new JavaParser(config);
        } catch (Exception e) {
            log.warn("Symbol solver setup failed, type resolution will be degraded: {}", e.getMessage());
            return new JavaParser();
        }
    }
}
