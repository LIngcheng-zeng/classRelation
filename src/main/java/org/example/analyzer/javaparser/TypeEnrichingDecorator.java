package org.example.analyzer.javaparser;

import org.example.analyzer.spoon.FieldTypeMap;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-processes {@link FieldMapping}s to repair syntactically noisy class names.
 *
 * A "noisy" className is one that contains {@code "()"} or {@code "."}  —
 * artefacts of heuristic scope extraction (e.g. {@code "build()"}, {@code "getOrder()"}).
 *
 * Strategy C (intentional):
 *   - className is noisy  → attempt repair via {@link FieldTypeMap}
 *   - className is null   → leave unchanged; no speculative guessing
 *
 * Disambiguation when multiple classes share the same field name:
 *   1. If exactly one candidate → use it
 *   2. If multiple candidates, exclude counterpart-side class names → if one remains, use it
 *   3. Otherwise → leave the FieldRef unchanged to avoid false positives
 */
class TypeEnrichingDecorator {

    private static final Logger log = LoggerFactory.getLogger(TypeEnrichingDecorator.class);

    private final FieldTypeMap fieldTypeMap;

    TypeEnrichingDecorator(FieldTypeMap fieldTypeMap) {
        this.fieldTypeMap = fieldTypeMap;
    }

    List<FieldMapping> enrich(List<FieldMapping> mappings) {
        if (fieldTypeMap == null) return mappings;
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
        if (className == null || !isNoisy(className)) return ref;   // Strategy C

        String repaired = resolveFromFieldTypeMap(ref.fieldName(), counterpart);
        if (repaired != null) {
            log.debug("TypeEnrichingDecorator: repaired [{} → {}].{}", className, repaired, ref.fieldName());
            return new FieldRef(repaired, ref.fieldName());
        }
        return ref;
    }

    private boolean isNoisy(String className) {
        return className.contains("(") || className.contains(".");
    }

    /**
     * Looks up which class(es) declare {@code fieldName}, then narrows via counterpart
     * class context if there are multiple candidates.
     */
    private String resolveFromFieldTypeMap(String fieldName, ExpressionSide counterpart) {
        Set<String> candidates = fieldTypeMap.getClassesForField(fieldName);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.iterator().next();

        // Multiple candidates: exclude classes already present on the counterpart side
        Set<String> counterpartClasses = counterpart.fields().stream()
                .map(FieldRef::className)
                .filter(n -> n != null && !isNoisy(n))
                .collect(Collectors.toSet());

        List<String> remaining = candidates.stream()
                .filter(c -> !counterpartClasses.contains(c))
                .collect(Collectors.toList());

        return remaining.size() == 1 ? remaining.get(0) : null;
    }
}
