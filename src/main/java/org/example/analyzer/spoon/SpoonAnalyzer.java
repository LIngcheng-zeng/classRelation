package org.example.analyzer.spoon;

import org.example.model.FieldMapping;
import org.example.resolution.SymbolResolutionResult;
import org.example.spi.SourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        List<FieldMapping> mappings = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                if (method.getBody() == null) continue;
                mappings.addAll(analyzeExecutable(method, model));
            }
        }

        log.info("SpoonAnalyzer found {} inter-procedural mapping(s)", mappings.size());
        return mappings;
    }

    // -------------------------------------------------------------------------

    private List<FieldMapping> analyzeExecutable(CtMethod<?> method, CtModel model) {
        Map<String, CtExpression<?>> aliasMap = SpoonAliasBuilder.build(method);
        if (aliasMap.isEmpty()) return List.of();

        CallProjectionExtractor extractor = new CallProjectionExtractor();
        extractor.extract(method, aliasMap, model);
        return extractor.results();
    }
}
