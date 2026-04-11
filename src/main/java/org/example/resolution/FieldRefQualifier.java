package org.example.resolution;

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
 * Normalises all {@link FieldRef} class names to fully-qualified names (FQN).
 *
 * Applied once after all extractors have run, so every downstream consumer
 * (LineageGraph, TransitiveClosureExpander, renderers) sees consistent FQN keys.
 *
 * Two-phase qualification:
 *   Phase 1 — Repair: class names containing "()" are method-call noise; resolve to FQN
 *             via {@link FieldTypeMap} field-ownership lookup.
 *   Phase 2 — Qualify: remaining simple names (no dot, no parens) are promoted to FQN
 *             using the classPackageIndex built by {@link SymbolResolver}.
 *
 * Null class names and already-qualified FQNs are left unchanged.
 */
public class FieldRefQualifier {

    private static final Logger log = LoggerFactory.getLogger(FieldRefQualifier.class);

    private final FieldTypeMap        fieldTypeIndex;
    private final Map<String, String> classPackageIndex;  // simpleName → FQN

    public FieldRefQualifier(SymbolResolutionResult symbols) {
        this.fieldTypeIndex    = symbols.fieldTypeIndex();
        this.classPackageIndex = symbols.classPackageIndex() != null
                                 ? symbols.classPackageIndex()
                                 : Map.of();
    }

    /**
     * Returns a new list where every FieldRef has been qualified where possible.
     * Mappings whose refs are already FQN are returned unchanged (same instance).
     */
    public List<FieldMapping> qualify(List<FieldMapping> mappings) {
        return mappings.stream()
                .map(this::qualifyMapping)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------

    private FieldMapping qualifyMapping(FieldMapping m) {
        ExpressionSide newLeft  = qualifySide(m.leftSide(),  m.rightSide());
        ExpressionSide newRight = qualifySide(m.rightSide(), m.leftSide());
        if (newLeft == m.leftSide() && newRight == m.rightSide()) return m;
        return new FieldMapping(newLeft, newRight, m.type(), m.mode(),
                m.rawExpression(), m.location(), m.normalization());
    }

    private ExpressionSide qualifySide(ExpressionSide side, ExpressionSide counterpart) {
        List<FieldRef> qualified = side.fields().stream()
                .map(ref -> qualifyRef(ref, counterpart))
                .collect(Collectors.toList());
        boolean changed = !qualified.equals(side.fields());
        return changed ? new ExpressionSide(qualified, side.operatorDesc()) : side;
    }

    private FieldRef qualifyRef(FieldRef ref, ExpressionSide counterpart) {
        String className = ref.className();
        if (className == null)   return ref;  // null → leave unchanged
        if (isFqn(className))    return ref;  // already qualified

        // Phase 1: noisy (contains parens) → repair via FieldTypeMap
        if (isNoisy(className)) {
            if (fieldTypeIndex == null) return ref;
            String repaired = resolveFromFieldTypeMap(ref.fieldName(), counterpart);
            if (repaired != null) {
                log.debug("FieldRefQualifier phase1: repaired [{} → {}].{}", className, repaired, ref.fieldName());
                return new FieldRef(repaired, ref.fieldName());
            }
            return ref;
        }

        // Phase 2: simple name → qualify via classPackageIndex
        String fqn = classPackageIndex.get(className);
        if (fqn != null) {
            log.debug("FieldRefQualifier phase2: qualified [{} → {}]", className, fqn);
            return new FieldRef(fqn, ref.fieldName());
        }

        return ref;
    }

    /** True when className already is a fully-qualified name (contains dot, no parens). */
    private boolean isFqn(String className) {
        return className.contains(".") && !className.contains("(");
    }

    /** True when className contains method-call noise from unresolved scope chains. */
    private boolean isNoisy(String className) {
        return className.contains("(");
    }

    /**
     * Resolves the FQN of the class declaring {@code fieldName},
     * disambiguating via counterpart-side class names when multiple candidates exist.
     */
    private String resolveFromFieldTypeMap(String fieldName, ExpressionSide counterpart) {
        Set<String> candidates = fieldTypeIndex.getClassesForField(fieldName);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.iterator().next();

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
