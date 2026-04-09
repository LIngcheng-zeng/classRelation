package org.example.expander;

import org.example.model.ClassRelation;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the transitive closure of field mappings.
 *
 * If A.f1 ≡ B.f2  and  B.f2 ≡ C.f3, derives A.f1 ≡ C.f3.
 *
 * Algorithm: fixed-point iteration with a seen-key set that prevents
 * both duplicate synthesis and infinite cycles.
 */
public class TransitiveClosureExpander {

    /**
     * @param relations original ClassRelation list
     * @return original relations unchanged + a separate list of derived relations
     */
    public List<ClassRelation> expand(List<ClassRelation> relations) {
        // Flatten all mappings for iteration
        List<FieldMapping> all = new ArrayList<>();
        for (ClassRelation rel : relations) {
            all.addAll(rel.mappings());
        }

        // Index: FieldKey → mappings whose leftSide contains that field
        Map<FieldKey, List<FieldMapping>> leftIndex = buildLeftIndex(all);

        // Seen keys prevent duplicates and cycles
        Set<PairKey> seenKeys = buildInitialSeenKeys(all);

        // Derived mappings accumulated across iterations
        List<FieldMapping> derived = new ArrayList<>();

        boolean added = true;
        while (added) {
            added = false;
            // Snapshot to avoid ConcurrentModificationException
            List<FieldMapping> snapshot = new ArrayList<>(all);

            for (FieldMapping m1 : snapshot) {
                for (FieldRef rfRight : m1.rightSide().fields()) {
                    if (rfRight.className() == null) continue;
                    FieldKey key = new FieldKey(rfRight.className(), rfRight.fieldName());

                    List<FieldMapping> candidates = leftIndex.getOrDefault(key, List.of());
                    for (FieldMapping m2 : candidates) {
                        if (m2 == m1) continue;

                        PairKey pairKey = new PairKey(m1.leftSide(), m2.rightSide());
                        if (seenKeys.contains(pairKey)) continue;

                        String rawExpr = "derived: [" + m1.rawExpression() + "] → [" + m2.rawExpression() + "]";
                        FieldMapping m3 = new FieldMapping(
                                m1.leftSide(),
                                m2.rightSide(),
                                MappingType.PARAMETERIZED,
                                MappingMode.TRANSITIVE_CLOSURE,
                                rawExpr,
                                "transitive"
                        );

                        derived.add(m3);
                        all.add(m3);
                        indexMapping(m3, leftIndex);
                        seenKeys.add(pairKey);
                        added = true;
                    }
                }
            }
        }

        if (derived.isEmpty()) return relations;

        // Merge derived mappings into ClassRelation structure (grouped by target class)
        Map<String, List<FieldMapping>> derivedByTarget = new LinkedHashMap<>();
        for (FieldMapping m : derived) {
            List<String> targetClasses = m.rightSide().fields().stream()
                    .map(FieldRef::className)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            for (String tgt : targetClasses) {
                derivedByTarget.computeIfAbsent(tgt, k -> new ArrayList<>()).add(m);
            }
        }

        List<ClassRelation> result = new ArrayList<>(relations);
        for (Map.Entry<String, List<FieldMapping>> entry : derivedByTarget.entrySet()) {
            result.add(new ClassRelation("__derived__", entry.getKey(), entry.getValue()));
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private Map<FieldKey, List<FieldMapping>> buildLeftIndex(List<FieldMapping> mappings) {
        Map<FieldKey, List<FieldMapping>> index = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            indexMapping(m, index);
        }
        return index;
    }

    private void indexMapping(FieldMapping m, Map<FieldKey, List<FieldMapping>> index) {
        for (FieldRef rf : m.leftSide().fields()) {
            if (rf.className() == null) continue;
            index.computeIfAbsent(new FieldKey(rf.className(), rf.fieldName()), k -> new ArrayList<>()).add(m);
        }
    }

    private Set<PairKey> buildInitialSeenKeys(List<FieldMapping> mappings) {
        Set<PairKey> seen = new HashSet<>();
        for (FieldMapping m : mappings) {
            seen.add(new PairKey(m.leftSide(), m.rightSide()));
        }
        return seen;
    }

    // -------------------------------------------------------------------------

    private record FieldKey(String className, String fieldName) {}

    private record PairKey(String leftFields, String rightFields) {
        PairKey(ExpressionSide left, ExpressionSide right) {
            this(toKey(left), toKey(right));
        }

        private static String toKey(ExpressionSide side) {
            if (side == null || side.isEmpty()) return "";
            return side.fields().stream()
                    .map(f -> f.className() + "." + f.fieldName())
                    .sorted()
                    .collect(Collectors.joining(","));
        }
    }
}
