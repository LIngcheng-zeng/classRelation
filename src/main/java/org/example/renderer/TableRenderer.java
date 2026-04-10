package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;

import java.util.*;

/**
 * Renders lineage relations as a plain-text table.
 *
 * Columns:
 *   目标表字段  |  构成目标字段的源表字段集合  |  类型  |  位置
 */
public class TableRenderer {

    public String render(List<ClassRelation> relations) {
        if (relations.isEmpty()) return "No class relations found.";

        // Collect all rows: [sinkFields, sourceFields, type, mode, location, normalization]
        List<String[]> rows = new ArrayList<>();
        for (ClassRelation rel : relations) {
            for (FieldMapping m : rel.mappings()) {
                String sinkFields   = formatSide(m.rightSide().fields());
                String sourceFields = formatSide(m.leftSide().fields());
                String type         = m.type().name();
                String mode         = m.mode() == org.example.model.MappingMode.WRITE_ASSIGNMENT ? "WRITE" : "READ";
                String location     = m.location();
                String normalization = m.normalization() != null && !m.normalization().isEmpty()
                        ? String.join(", ", m.normalization())
                        : "";
                rows.add(new String[]{ sinkFields, sourceFields, type, mode, location, normalization });
            }
        }

        // Column headers
        String[] headers = { "目标表字段", "源表字段集合", "映射类型", "模式", "代码位置", "归一化操作" };

        // Compute column widths
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = displayWidth(headers[i]);
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row[i]));
            }
        }

        // Build table
        StringBuilder sb = new StringBuilder();
        String separator = buildSeparator(widths);
        sb.append(separator).append("\n");
        sb.append(buildRow(headers, widths)).append("\n");
        sb.append(separator).append("\n");
        for (String[] row : rows) {
            sb.append(buildRow(row, widths)).append("\n");
        }
        sb.append(separator);
        return sb.toString();
    }

    private String formatSide(List<FieldRef> fields) {
        if (fields == null || fields.isEmpty()) return "<unknown>";
        return String.join(", ", fields.stream().map(FieldRef::toString).toList());
    }

    private String buildSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append("+");
        }
        return sb.toString();
    }

    private String buildRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i] == null ? "" : cells[i];
            int pad = widths[i] - displayWidth(cell);
            sb.append(" ").append(cell).append(" ".repeat(pad)).append(" |");
        }
        return sb.toString();
    }

    /**
     * Approximate display width: Chinese characters count as 2, others as 1.
     */
    private int displayWidth(String s) {
        int width = 0;
        for (char c : s.toCharArray()) {
            width += (c > 0x7F) ? 2 : 1;
        }
        return width;
    }
}
