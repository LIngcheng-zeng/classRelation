package org.example.analyzer.spoon;

import org.example.model.FieldMapping;
import org.example.spi.SourceAnalyzer;
import spoon.Launcher;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Spoon-based implementation of {@link SourceAnalyzer}.
 *
 * Focuses exclusively on inter-procedural field mappings that JavaParser cannot detect:
 * cases where a field value is passed as an argument to another method, and inside that
 * method the value is written to a different object's field.
 *
 * Uses Eclipse JDT-level compilation for accurate type resolution, and
 * {@code CtInvocation.getExecutable().getDeclaration()} for direct callee navigation.
 *
 * To add a new Spoon-based detection pattern, implement the extraction logic
 * in a separate class and call it from {@link #analyzeExecutable}.
 */
public class SpoonAnalyzer implements SourceAnalyzer {

    private static final Logger log = Logger.getLogger(SpoonAnalyzer.class.getName());

    @Override
    public List<FieldMapping> analyze(Path projectRoot) {
        Launcher launcher = buildLauncher(projectRoot);
        if (launcher == null) return List.of();

        CtModel model = launcher.getModel();
        List<FieldMapping> mappings = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                if (method.getBody() == null) continue;
                mappings.addAll(analyzeExecutable(method, model));
            }
        }

        log.info("SpoonAnalyzer found " + mappings.size() + " inter-procedural mapping(s)");
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

    private Launcher buildLauncher(Path projectRoot) {
        try {
            Launcher launcher = new Launcher();
            launcher.addInputResource(projectRoot.toString());
            launcher.getEnvironment().setAutoImports(true);
            launcher.getEnvironment().setNoClasspath(true);   // tolerate missing deps
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.buildModel();
            return launcher;
        } catch (Exception e) {
            log.warning("SpoonAnalyzer: failed to build model for " + projectRoot + " — " + e.getMessage());
            return null;
        }
    }
}
