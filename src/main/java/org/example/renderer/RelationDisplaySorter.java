package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.util.ClassNameValidator;

import java.util.Comparator;
import java.util.List;

/**
 * Provides the canonical 4-level sort order for display:
 *   1. target class simple name
 *   2. target field name
 *   3. source class simple name
 *   4. source field name
 */
public final class RelationDisplaySorter {

    private RelationDisplaySorter() {}

    public static List<ClassRelation> sortByTargetClass(List<ClassRelation> relations) {
        return relations.stream()
                .sorted(Comparator.comparing(r -> ClassNameValidator.extractSimpleName(r.targetClass())))
                .toList();
    }

    public static List<FieldMapping> sortMappings(List<FieldMapping> mappings) {
        return mappings.stream()
                .sorted(Comparator
                        .comparing((FieldMapping m) -> sinkFieldKey(m.rightSide()))
                        .thenComparing(m -> sourceClassKey(m.leftSide()))
                        .thenComparing(m -> sourceFieldKey(m.leftSide())))
                .toList();
    }

    private static String sinkFieldKey(ExpressionSide side) {
        if (side == null || side.isEmpty()) return "";
        return side.fields().stream().map(FieldRef::fieldName).sorted().reduce("", (a, b) -> a + "," + b);
    }

    private static String sourceClassKey(ExpressionSide side) {
        if (side == null || side.isEmpty()) return "";
        return side.fields().stream()
                .map(f -> ClassNameValidator.extractSimpleName(f.className()))
                .sorted()
                .reduce("", (a, b) -> a + "," + b);
    }

    private static String sourceFieldKey(ExpressionSide side) {
        if (side == null || side.isEmpty()) return "";
        return side.fields().stream()
                .map(FieldRef::fieldName)
                .sorted()
                .reduce("", (a, b) -> a + "," + b);
    }
}
