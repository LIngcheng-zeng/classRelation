package org.example.analyzer.spoon.implicit;

import org.example.analyzer.javaparser.JavaParserAnalyzer;
import org.example.model.MapFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Batch-level registry of {@link MapFact}s discovered outside individual method bodies.
 *
 * Built once per analysis batch by {@link GlobalMapRegistryBuilder} before any
 * per-method extraction runs, then injected into {@link ImplicitEqualityExtractor}.
 *
 * Three orthogonal namespaces track the three cross-file Map patterns:
 *
 *   fieldFacts       — instance fields of Map type (Scenario 1)
 *                      key: "ClassName#fieldName"
 *
 *   methodReturnFacts — methods whose return type is Map (Scenario 2)
 *                       key: "ClassName#methodName"
 *                       Populated for both explicit return and getter wrappers.
 *
 *   staticFacts      — static fields of Map type (Scenario 3)
 *                      key: "ClassName#fieldName"
 *
 * All lookups are by fully-qualified class name where available; falls back to
 * simple name only in Spoon noClasspath mode when FQN cannot be resolved.
 */
public final class GlobalMapRegistry {
    private static final Logger log = LoggerFactory.getLogger(GlobalMapRegistry.class);
    private final Map<String, MapFact> fieldFacts        = new LinkedHashMap<>();
    private final Map<String, MapFact> methodReturnFacts = new LinkedHashMap<>();
    private final Map<String, MapFact> staticFacts       = new LinkedHashMap<>();

    // ── registration ─────────────────────────────────────────────────────────

    public void registerFieldFact(String className, String fieldName, MapFact fact) {
        log.info("Registering field fact: " + className + " , " + fieldName);
        fieldFacts.put(key(className, fieldName), fact);
    }

    public void registerReturnFact(String className, String methodName, MapFact fact) {
        methodReturnFacts.put(key(className, methodName), fact);
    }

    public void registerStaticFact(String className, String fieldName, MapFact fact) {
        staticFacts.put(key(className, fieldName), fact);
    }

    // ── lookup ────────────────────────────────────────────────────────────────

    /** Scenario 1: instance field of Map type. */
    public Optional<MapFact> lookupField(String className, String fieldName) {
        return Optional.ofNullable(fieldFacts.get(key(className, fieldName)));
    }

    /** Scenario 2: method whose return type is a Map (includes generated getters). */
    public Optional<MapFact> lookupMethodReturn(String className, String methodName) {
        return Optional.ofNullable(methodReturnFacts.get(key(className, methodName)));
    }

    /** Scenario 3: static field of Map type. */
    public Optional<MapFact> lookupStatic(String className, String fieldName) {
        return Optional.ofNullable(staticFacts.get(key(className, fieldName)));
    }

    // ── diagnostics ──────────────────────────────────────────────────────────

    public int size() {
        return fieldFacts.size() + methodReturnFacts.size() + staticFacts.size();
    }

    /** Returns an empty registry (used when no batch model is available). */
    public static GlobalMapRegistry empty() {
        return new GlobalMapRegistry();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static String key(String className, String memberName) {
        return className + "#" + memberName;
    }
}
