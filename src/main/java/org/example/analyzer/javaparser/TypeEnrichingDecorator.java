package org.example.analyzer.javaparser;

import org.example.analyzer.spoon.FieldTypeMap;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-processes {@link FieldMapping}s to produce fully-qualified class names
 * in all {@link FieldRef}s.
 *
 * Two-phase enrichment:
 *   Phase 1 — Repair: noisy class names (containing "()") are resolved to FQN
 *             via {@link FieldTypeMap} (which indexes fields by declaring FQN).
 *   Phase 2 — Qualify: remaining simple class names (no dots, no parens) are
 *             promoted to FQN using the classPackageMap built by JavaFileScanner.
 *
 * Strategy C (intentional): null className is left unchanged; no speculative guessing.
 */
class TypeEnrichingDecorator {

    private static final Logger log = LoggerFactory.getLogger(TypeEnrichingDecorator.class);

    private final FieldTypeMap         fieldTypeMap;
    private final Map<String, String>  classPackageMap;  // simpleName → FQN

    TypeEnrichingDecorator(FieldTypeMap fieldTypeMap, Map<String, String> classPackageMap) {
        this.fieldTypeMap    = fieldTypeMap;
        this.classPackageMap = classPackageMap != null ? classPackageMap : Map.of();
    }

    List<FieldMapping> enrich(List<FieldMapping> mappings) {
        return mappings.stream()
                .map(this::enrichMapping)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------

    private FieldMapping enrichMapping(FieldMapping m) {
        ExpressionSide newLeft  = enrichSide(m.leftSide(),  m.rightSide());
        ExpressionSide newRight = enrichSide(m.rightSide(), m.leftSide());
        if (newLeft == m.leftSide() && newRight == m.rightSide()) return m;
        return new FieldMapping(newLeft, newRight, m.type(), m.mode(),
                m.rawExpression(), m.location(), m.normalization());
    }

    private ExpressionSide enrichSide(ExpressionSide side, ExpressionSide counterpart) {
        List<FieldRef> enriched = side.fields().stream()
                .map(ref -> enrichRef(ref, counterpart))
                .collect(Collectors.toList());
        boolean changed = !enriched.equals(side.fields());
        return changed ? new ExpressionSide(enriched, side.operatorDesc()) : side;
    }

    private FieldRef enrichRef(FieldRef ref, ExpressionSide counterpart) {
        String className = ref.className();
        if (className == null) return ref;   // Strategy C: null → leave unchanged

        // Already a FQN (contains dot, no parens) — nothing to do
        if (isFqn(className)) return ref;

        // Phase 1: noisy name (contains parens) → repair to FQN via FieldTypeMap
        if (isNoisy(className)) {
            if (fieldTypeMap == null) return ref;
            String repaired = resolveFromFieldTypeMap(ref.fieldName(), counterpart);
            if (repaired != null) {
                log.debug("TypeEnrichingDecorator phase1: repaired [{} → {}].{}", className, repaired, ref.fieldName());
                return new FieldRef(repaired, ref.fieldName());
            }
            return ref;
        }

        // Phase 2: simple name (no dot, no parens) → qualify via classPackageMap
        String fqn = classPackageMap.get(className);
        if (fqn != null) {
            log.debug("TypeEnrichingDecorator phase2: qualified [{} → {}]", className, fqn);
            return new FieldRef(fqn, ref.fieldName());
        }

        return ref;
    }

    /** Returns true when className is already a fully-qualified name. */
    private boolean isFqn(String className) {
        return className.contains(".") && !className.contains("(");
    }

    /** Returns true when className contains method-call noise. */
    private boolean isNoisy(String className) {
        return className.contains("(");
    }

    /**
     * Resolves the FQN of the class declaring {@code fieldName},
     * disambiguating via counterpart-side class names when multiple candidates exist.
     */
    private String resolveFromFieldTypeMap(String fieldName, ExpressionSide counterpart) {
        Set<String> candidates = fieldTypeMap.getClassesForField(fieldName);  // returns FQN set
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.iterator().next();

        // Multiple candidates: exclude FQNs already present on the counterpart side
        Set<String> counterpartFqns = counterpart.fields().stream()
                .map(FieldRef::className)
                .filter(n -> n != null && isFqn(n))
                .collect(Collectors.toSet());

        List<String> remaining = candidates.stream()
                .filter(c -> !counterpartFqns.contains(c))
                .collect(Collectors.toList());

        return remaining.size() == 1 ? remaining.get(0) : null;
    }
}
