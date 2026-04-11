package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.util.ClassNameValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders lineage relations as a plain-text table.
 *
 * Layout:
 *   - One section per target class, headed by "=== ClassName ==="
 *   - Rows sorted by target field name (alphabetical)
 *   - Same target field with multiple sources: first source occupies the
 *     目标字段 cell; continuation sources leave it blank (rowspan simulation)
 *
 * Columns:
 *   目标字段 | 源字段 | 映射类型 | 模式 | 代码位置 | 归一化操作
 */
public class TableRenderer {

    private static final String[] HEADERS =
            { "目标字段", "源字段", "映射类型", "模式", "代码位置", "归一化操作" };

    public String render(List<ClassRelation> relations) {
        if (relations.isEmpty()) return "No class relations found.";

        // Aggregate all mappings by target class, preserving encounter order
        Map<String, List<FieldMapping>> byTarget = new LinkedHashMap<>();
        for (ClassRelation rel : relations) {
            byTarget.computeIfAbsent(rel.targetClass(), k -> new ArrayList<>())
                    .addAll(rel.mappings());
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, List<FieldMapping>> entry : byTarget.entrySet()) {
            List<FieldMapping> fieldMappings = entry.getValue().stream()
                    .filter(m -> !isComposition(m))
                    .toList();
            if (fieldMappings.isEmpty()) continue;   // section has only composition relations

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
     * Groups mappings by sink field (sorted alphabetically), then flattens into
     * display rows using the continuation-blank pattern for multi-source fields.
     *
     * Deduplication: (sinkField, sourceField) pairs seen more than once — e.g. the
     * same mapping detected via multiple code paths — are collapsed to a single row.
     */
    private List<String[]> buildRows(List<FieldMapping> mappings) {
        // TreeMap: alphabetical sort by sink field display string
        Map<String, List<FieldMapping>> bySinkField = new TreeMap<>();
        for (FieldMapping m : mappings) {
            String key = formatSinkKey(m.rightSide().fields());
            bySinkField.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<FieldMapping>> fieldEntry : bySinkField.entrySet()) {
            String sinkDisplay = fieldEntry.getKey();
            boolean firstRow   = true;
            java.util.Set<String> seenSources = new java.util.LinkedHashSet<>();
            for (FieldMapping m : fieldEntry.getValue()) {
                String sourceKey    = formatSourceSide(m.leftSide().fields());
                String sourceDedupKey = buildSourceDedupKey(m.leftSide().fields());
                if (!seenSources.add(sourceDedupKey)) continue;   // duplicate (sink, source) pair
                rows.add(new String[]{
                        firstRow ? sinkDisplay : "",
                        sourceKey,
                        m.type().name(),
                        m.mode() == MappingMode.WRITE_ASSIGNMENT ? "WRITE" : "READ",
                        m.location(),
                        normalization(m)
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

    private String normalization(FieldMapping m) {
        return (m.normalization() != null && !m.normalization().isEmpty())
                ? String.join(", ", m.normalization())
                : "";
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
