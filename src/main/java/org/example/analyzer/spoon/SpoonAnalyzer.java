package org.example.analyzer.spoon;

import org.example.analyzer.spoon.implicit.GlobalMapRegistry;
import org.example.analyzer.spoon.implicit.GlobalMapRegistryBuilder;
import org.example.model.FieldMapping;
import org.example.resolution.SymbolResolutionResult;
import org.example.spi.SourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spoon-based implementation of {@link SourceAnalyzer}.
 *
 * Focuses exclusively on inter-procedural field mappings that JavaParser cannot detect:
 * cases where a field value is passed as an argument to another method, and inside that
 * method the value is written to a different object's field.
 *
 * Symbol resolution (CtModel, FieldTypeMap, inheritance) is performed once by
 * {@link org.example.resolution.SymbolResolver} and passed in via {@link SymbolResolutionResult}.
 */
public class SpoonAnalyzer implements SourceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SpoonAnalyzer.class);

    @Override
    public List<FieldMapping> analyze(Path projectRoot, SymbolResolutionResult symbols) {
        CtModel model = symbols.spoonModel();
        if (model == null) {
            log.warn("SpoonAnalyzer: no Spoon model available, skipping");
            return List.of();
        }

        long t0 = System.currentTimeMillis();

        // Build once per analysis batch — NOT per method.
        // SpoonResolutionHelper pre-indexes all types (O(1) FQN lookup).
        // GlobalMapRegistry scans all types once for MAP_JOIN detection.
        SpoonResolutionHelper helper   = new SpoonResolutionHelper(model);
        GlobalMapRegistry     registry = new GlobalMapRegistryBuilder().build(model);

        long buildMs = System.currentTimeMillis() - t0;
        log.info("[PERF] SpoonAnalyzer batch init (helper+registry): {}ms", buildMs);

        // Parallel stream over all methods.
        // Thread-safety guaranteed:
        //   - helper     : read-only after construction (fqnToType is unmodifiable)
        //   - registry   : immutable after GlobalMapRegistryBuilder.build()
        //   - per-method : each lambda gets its own CallProjectionExtractor + ExecutionContext
        //   - CtModel    : Spoon AST is built before analysis and never mutated here
        long t1 = System.currentTimeMillis();
        List<FieldMapping> mappings = model.getAllTypes().stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.getBody() != null)
                .parallel()
                .flatMap(method -> analyzeExecutable(method, helper, registry).stream())
                .collect(Collectors.toList());

        log.info("[PERF] SpoonAnalyzer: {}ms  mappings={}", System.currentTimeMillis() - t1, mappings.size());
        return mappings;
    }

    // -------------------------------------------------------------------------

    private List<FieldMapping> analyzeExecutable(CtMethod<?> method,
                                                  SpoonResolutionHelper helper,
                                                  GlobalMapRegistry registry) {
        ExecutionContext ctx = ExecutionContext.forMethod(method);
        CallProjectionExtractor extractor = new CallProjectionExtractor(helper, registry);
        extractor.extract(method, ctx);
        return extractor.results();
    }
}
