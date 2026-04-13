package org.example.resolution;

import org.example.analyzer.javaparser.JavaFileScanner;
import org.example.analyzer.spoon.FieldTypeMap;
import org.example.model.ClassRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.nio.file.Path;
import java.util.*;

/**
 * Builds a {@link SymbolResolutionResult} before any extraction begins.
 *
 * Responsibilities (all side-effect-free after the call returns):
 *   1. Launch Spoon, build CtModel, derive {@link FieldTypeMap} and inheritance index
 *   2. Walk the file tree once to collect .java paths and the class-package index
 *
 * Consumers (SpoonAnalyzer, JavaParserAnalyzer) receive the result as a read-only value —
 * no implicit ordering dependency, no shared mutable state.
 */
public class SymbolResolver {

    private static final Logger log = LoggerFactory.getLogger(SymbolResolver.class);

    private final JavaFileScanner scanner = new JavaFileScanner();

    public SymbolResolutionResult resolve(Path projectRoot) {
        // File scan: produces java file list + classPackageIndex
        long ts0 = System.currentTimeMillis();
        JavaFileScanner.ScanResult scan = scanner.scan(projectRoot);
        log.info("[PERF] SymbolResolver.scan: {}ms  files={}", System.currentTimeMillis() - ts0, scan.javaFiles().size());

        // Spoon model: produces fieldTypeIndex + inheritanceIndex + CtModel
        long ts1 = System.currentTimeMillis();
        CtModel model = buildSpoonModel(projectRoot);
        log.info("[PERF] SymbolResolver.buildSpoonModel: {}ms", System.currentTimeMillis() - ts1);

        long ts2 = System.currentTimeMillis();
        FieldTypeMap ftIndex  = model != null ? FieldTypeMap.build(model) : null;
        log.info("[PERF] SymbolResolver.buildFieldTypeMap: {}ms", System.currentTimeMillis() - ts2);

        long ts3 = System.currentTimeMillis();
        Map<String, ClassRelation.InheritanceInfo> inheritanceIndex =
                model != null ? detectInheritance(model) : Map.of();
        log.info("[PERF] SymbolResolver.detectInheritance: {}ms  entries={}", System.currentTimeMillis() - ts3, inheritanceIndex.size());

        if (ftIndex != null) log.info("SymbolResolver built {}", ftIndex);
        log.info("SymbolResolver classPackageIndex: {} entries", scan.classPackageIndex().size());

        return new SymbolResolutionResult(
                ftIndex,
                scan.classPackageIndex(),
                inheritanceIndex,
                model,
                scan.javaFiles()
        );
    }

    // -------------------------------------------------------------------------

    private CtModel buildSpoonModel(Path projectRoot) {
        try {
            Launcher launcher = new Launcher();
            launcher.addInputResource(projectRoot.toString());
            launcher.getEnvironment().setAutoImports(true);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.buildModel();
            return launcher.getModel();
        } catch (Exception e) {
            log.warn("SymbolResolver: failed to build Spoon model for {} — {}", projectRoot, e.getMessage());
            return null;
        }
    }

    private Map<String, ClassRelation.InheritanceInfo> detectInheritance(CtModel model) {
        Map<String, ClassRelation.InheritanceInfo> result = new LinkedHashMap<>();

        for (CtType<?> type : model.getAllTypes()) {
            try {
                CtTypeReference<?> superclass = type.getSuperclass();
                if (superclass == null || superclass.getQualifiedName().equals("java.lang.Object")) continue;

                String childClass  = type.getQualifiedName();
                String parentClass = superclass.getQualifiedName();

                List<String> inheritedFields = new ArrayList<>();
                try {
                    var parentType = superclass.getDeclaration();
                    if (parentType != null) {
                        for (var fieldRef : parentType.getDeclaredFields()) {
                            inheritedFields.add(fieldRef.getSimpleName());
                        }
                    }
                } catch (Exception ignored) {}

                result.put(childClass, new ClassRelation.InheritanceInfo(
                        childClass, parentClass, inheritedFields));

                log.debug("Inheritance: {} extends {} (fields: {})", childClass, parentClass, inheritedFields);

            } catch (Exception e) {
                log.debug("Failed to detect inheritance for {}: {}", type.getSimpleName(), e.getMessage());
            }
        }

        return Collections.unmodifiableMap(result);
    }
}
