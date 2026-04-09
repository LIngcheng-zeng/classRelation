package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders lineage analysis results as a single Markdown document.
 * Sections: summary → Mermaid diagram → field lineage table.
 */
public class MarkdownDocumentRenderer {

    private final MermaidRenderer mermaidRenderer = new MermaidRenderer();

    public String render(String projectName, List<ClassRelation> relations) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(projectName).append(" — 字段关联分析报告\n\n");

        // Summary
        long readCount  = relations.stream().flatMap(r -> r.mappings().stream())
                .filter(m -> m.mode() == MappingMode.READ_PREDICATE).count();
        long writeCount = relations.stream().flatMap(r -> r.mappings().stream())
                .filter(m -> m.mode() == MappingMode.WRITE_ASSIGNMENT).count();
        sb.append("## 摘要\n\n");
        sb.append("| 项目 | 数值 |\n");
        sb.append("|---|---|\n");
        sb.append("| 涉及类关系对 | ").append(relations.size()).append(" |\n");
        sb.append("| 探测型关联（READ） | ").append(readCount).append(" |\n");
        sb.append("| 动作型关联（WRITE） | ").append(writeCount).append(" |\n\n");

        // Mermaid diagram
        sb.append("## 关联图谱\n\n");
        sb.append(mermaidRenderer.render(relations)).append("\n\n");
        sb.append("> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。\n\n");

        // Field lineage table — grouped by target class
        sb.append("## 字段血缘明细\n\n");

        // Collect mappings grouped by target class name
        Map<String, List<FieldMapping>> byTarget = new LinkedHashMap<>();
        for (ClassRelation rel : relations) {
            byTarget.computeIfAbsent(rel.targetClass(), k -> new java.util.ArrayList<>())
                    .addAll(rel.mappings());
        }

        for (Map.Entry<String, List<FieldMapping>> entry : byTarget.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n\n");
            sb.append("| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |\n");
            sb.append("|---|---|---|---|---|\n");

            for (FieldMapping m : entry.getValue()) {
                String sink    = formatSide(m.rightSide());
                String source  = formatSide(m.leftSide());
                String type    = m.type().name();
                String mode    = m.mode() == MappingMode.WRITE_ASSIGNMENT ? "WRITE" : "READ";
                String loc     = m.location();
                String rawExpr = escape(m.rawExpression());
                sb.append("| ").append(sink)
                  .append(" | ").append(source)
                  .append(" | ").append(type)
                  .append(" | ").append(mode)
                  .append(" | `").append(loc).append("` |\n");
                sb.append("| | *").append(rawExpr).append("* | | | |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatSide(org.example.model.ExpressionSide side) {
        if (side == null || side.isEmpty()) return "`<unknown>`";
        return String.join(", ", side.fields().stream()
                .map(f -> "`" + f.toString() + "`")
                .toList());
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
