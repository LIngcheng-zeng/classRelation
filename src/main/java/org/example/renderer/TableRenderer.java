package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.util.ClassNameValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Renders lineage relations as a plain-text table.
 *
 * Layout:
 *   - Sections sorted by target class simple name (alphabetical)
 *   - Rows sorted: target field → source class → source field
 *   - Same target field with multiple sources: first source occupies the
 *     目标字段 cell; continuation rows leave it blank (rowspan simulation)
 *
 * Columns:
 *   目标字段 | 源端字段 | 代码位置 | 代码块
 */
public class TableRenderer {

    private static final String[] HEADERS =
            { "目标字段", "源端字段", "代码位置", "代码块" };

    public String render(List<ClassRelation> relations) {
        if (relations.isEmpty()) return "No class relations found.";

        // Sort by target class simple name (level-1), then aggregate
        List<ClassRelation> sorted = RelationDisplaySorter.sortByTargetClass(relations);
        Map<String, List<FieldMapping>> byTarget = new LinkedHashMap<>();
        for (ClassRelation rel : sorted) {
            byTarget.computeIfAbsent(rel.targetClass(), k -> new ArrayList<>())
                    .addAll(rel.mappings());
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, List<FieldMapping>> entry : byTarget.entrySet()) {
            List<FieldMapping> fieldMappings = entry.getValue().stream()
                    .filter(m -> !isComposition(m))
                    .toList();
            if (fieldMappings.isEmpty()) continue;

            if (!first) sb.append("\n");
            first = false;

            String simpleName = ClassNameValidator.extractSimpleName(entry.getKey());
            sb.append("=== ").append(simpleName).append(" ===\n");

            List<String[]> rows = buildRows(fieldMappings);
            renderTable(sb, rows);
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------

    /**
     * Applies the 4-level sort via {@link RelationDisplaySorter}, groups by sink field,
     * deduplicates (sinkField, sourceField) pairs, and produces display rows with the
     * blank-continuation pattern for merged-cell simulation.
     */
    private List<String[]> buildRows(List<FieldMapping> mappings) {
        List<FieldMapping> sorted = RelationDisplaySorter.sortMappings(mappings);

        // LinkedHashMap preserves the sort order produced above
        Map<String, List<FieldMapping>> bySinkField = new LinkedHashMap<>();
        for (FieldMapping m : sorted) {
            bySinkField.computeIfAbsent(formatSinkKey(m.rightSide().fields()), k -> new ArrayList<>()).add(m);
        }

        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<FieldMapping>> fieldEntry : bySinkField.entrySet()) {
            String sinkDisplay = fieldEntry.getKey();
            boolean firstRow = true;
            LinkedHashSet<String> seenSources = new LinkedHashSet<>();
            for (FieldMapping m : fieldEntry.getValue()) {
                String dedupKey = buildSourceDedupKey(m.leftSide().fields());
                if (!seenSources.add(dedupKey)) continue;
                rows.add(new String[]{
                        firstRow ? sinkDisplay : "",
                        formatSourceSide(m.leftSide().fields()),
                        m.location(),
                        m.rawExpression() != null ? m.rawExpression() : ""
                });
                firstRow = false;
            }
        }
        return rows;
    }

    private void renderTable(StringBuilder sb, List<String[]> rows) {
        int[] widths = computeWidths(rows);
        String sep   = buildSeparator(widths);

        sb.append(sep).append("\n");
        sb.append(buildRow(HEADERS, widths)).append("\n");
        sb.append(sep).append("\n");
        for (String[] row : rows) {
            sb.append(buildRow(row, widths)).append("\n");
        }
        sb.append(sep).append("\n");
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    /** Sink field display: field names only (class shown in section header). */
    private String formatSinkKey(List<FieldRef> fields) {
        if (fields == null || fields.isEmpty()) return "<unknown>";
        return String.join(", ", fields.stream().map(FieldRef::fieldName).toList());
    }

    /** Source field display: ClassName.fieldName. */
    private String formatSourceSide(List<FieldRef> fields) {
        if (fields == null || fields.isEmpty()) return "<unknown>";
        return String.join(", ", fields.stream().map(FieldRef::toString).toList());
    }

    /**
     * Deduplication key for a source side: uses simple class names (strips FQN prefix)
     * so that "org.example.model.Employee.fullName" and "Employee.fullName" collapse to
     * the same key after FieldRefQualifier has normalized them.
     */
    private String buildSourceDedupKey(List<FieldRef> fields) {
        if (fields == null || fields.isEmpty()) return "<unknown>";
        return String.join(", ", fields.stream()
                .map(f -> ClassNameValidator.extractSimpleName(f.className()) + "." + f.fieldName())
                .toList());
    }

    // -------------------------------------------------------------------------
    // Table-building primitives
    // -------------------------------------------------------------------------

    private int[] computeWidths(List<String[]> rows) {
        int[] widths = new int[HEADERS.length];
        for (int i = 0; i < HEADERS.length; i++) widths[i] = displayWidth(HEADERS[i]);
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row[i]));
            }
        }
        return widths;
    }

    private String buildSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) sb.append("-".repeat(w + 2)).append("+");
        return sb.toString();
    }

    private String buildRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i] == null ? "" : cells[i];
            int    pad  = widths[i] - displayWidth(cell);
            sb.append(" ").append(cell).append(" ".repeat(pad)).append(" |");
        }
        return sb.toString();
    }

    private boolean isComposition(FieldMapping m) {
        return "composition-sink".equals(m.rightSide().operatorDesc());
    }

    /** Chinese characters count as 2 columns, ASCII as 1. */
    private int displayWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += (c > 0x7F) ? 2 : 1;
        return w;
    }
}
