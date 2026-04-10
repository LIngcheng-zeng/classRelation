package org.example.analyzer.spoon;

import org.example.model.FieldMapping;
import org.example.spi.AnalysisContext;
import org.example.spi.SourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SpoonAnalyzer.class);

    @Override
    public List<FieldMapping> analyze(Path projectRoot) {
        return analyze(projectRoot, new AnalysisContext());
    }

    @Override
    public List<FieldMapping> analyze(Path projectRoot, AnalysisContext ctx) {
        Launcher launcher = buildLauncher(projectRoot);
        if (launcher == null) return List.of();

        CtModel model = launcher.getModel();

        // Build and publish FieldTypeMap so JavaParserAnalyzer can consume it
        ctx.fieldTypeMap = FieldTypeMap.build(model);
        log.info("SpoonAnalyzer built {}", ctx.fieldTypeMap);
        
        // Detect inheritance relationships
        ctx.inheritanceMap = detectInheritance(model);
        log.info("SpoonAnalyzer detected {} inheritance relationship(s)", ctx.inheritanceMap.size());

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
    
    /**
     * Detects inheritance relationships in the model.
     * Returns a map: childClassName -> InheritanceInfo
     */
    private Map<String, org.example.model.ClassRelation.InheritanceInfo> detectInheritance(CtModel model) {
        Map<String, org.example.model.ClassRelation.InheritanceInfo> inheritanceMap = new java.util.HashMap<>();
        
        for (CtType<?> type : model.getAllTypes()) {
            try {
                spoon.reflect.reference.CtTypeReference<?> superclass = type.getSuperclass();
                if (superclass != null && !superclass.getQualifiedName().equals("java.lang.Object")) {
                    String childClass = type.getSimpleName();
                    String parentClass = superclass.getSimpleName();
                    
                    // Collect inherited fields (fields declared in parent class)
                    List<String> inheritedFields = new ArrayList<>();
                    try {
                        spoon.reflect.declaration.CtType<?> parentType = superclass.getDeclaration();
                        if (parentType != null) {
                            for (spoon.reflect.reference.CtFieldReference<?> fieldRef : parentType.getDeclaredFields()) {
                                inheritedFields.add(fieldRef.getSimpleName());
                            }
                        }
                    } catch (Exception ignored) {}
                    
                    inheritanceMap.put(childClass, new org.example.model.ClassRelation.InheritanceInfo(
                        childClass, parentClass, inheritedFields
                    ));
                    
                    log.debug("Found inheritance: {} extends {} (fields: {})", 
                             childClass, parentClass, inheritedFields);
                }
            } catch (Exception e) {
                log.debug("Failed to detect inheritance for {}: {}", type.getSimpleName(), e.getMessage());
            }
        }
        
        return inheritanceMap;
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
            log.warn("SpoonAnalyzer: failed to build model for {} — {}", projectRoot, e.getMessage());
            return null;
        }
    }
}
