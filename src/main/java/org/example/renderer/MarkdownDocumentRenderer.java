package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        long readCount       = relations.stream().flatMap(r -> r.mappings().stream())
                .filter(m -> m.mode() == MappingMode.READ_PREDICATE).count();
        long writeCount      = relations.stream().flatMap(r -> r.mappings().stream())
                .filter(m -> m.mode() == MappingMode.WRITE_ASSIGNMENT).count();
        long transitiveCount = relations.stream().flatMap(r -> r.mappings().stream())
                .filter(m -> m.mode() == MappingMode.TRANSITIVE_CLOSURE).count();
        long directRelCount  = relations.stream()
                .filter(r -> r.mappings().stream().anyMatch(m -> m.mode() != MappingMode.TRANSITIVE_CLOSURE))
                .count();
        sb.append("## 摘要\n\n");
        sb.append("| 项目 | 数值 |\n");
        sb.append("|---|---|\n");
        sb.append("| 涉及类关系对（直接） | ").append(directRelCount).append(" |\n");
        sb.append("| 探测型关联（READ） | ").append(readCount).append(" |\n");
        sb.append("| 动作型关联（WRITE） | ").append(writeCount).append(" |\n");
        sb.append("| 推导关联（传递闭包） | ").append(transitiveCount).append(" |\n\n");

        // Mermaid diagram
        sb.append("## 关联图谱\n\n");
        sb.append(mermaidRenderer.render(relations)).append("\n\n");
        sb.append("> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。\n\n");
        
        // Legend for relationship type abbreviations
        sb.append("### 关系类型说明\n\n");
        sb.append("| 缩写 | 全称 | 含义 | 示例 |\n");
        sb.append("|---|---|---|---|\n");
        sb.append("| **AE** | Atomic Equality | 原子等值：单字段对单字段的直接映射 | `A.id ≡ B.userId` |\n");
        sb.append("| **CP** | Composite Projection | 投影组合：多字段组合或拼接后的映射 | `A.f1 + A.f2 ≡ B.full` |\n");
        sb.append("| **PD** | Parameterized / Derived | 参数化/派生：经过转换、归一化或依赖上下文的映射 | `A.code.toLowerCase() ≡ B.value` |\n\n");
        
        // Inheritance relationships section
        List<ClassRelation.InheritanceInfo> inheritances = relations.stream()
                .map(ClassRelation::inheritance)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        
        if (!inheritances.isEmpty()) {
            sb.append("### 继承关系\n\n");
            sb.append("| 子类 | 父类 | 继承字段 |\n");
            sb.append("|---|---|---|\n");
            for (ClassRelation.InheritanceInfo info : inheritances) {
                String fields = info.inheritedFields().isEmpty()
                    ? "-"
                    : String.join(", ", info.inheritedFields());
                sb.append("| `").append(info.simpleChildClass()).append("`")
                  .append(" | `").append(info.simpleParentClass()).append("`")
                  .append(" | `").append(fields).append("` |\n");
            }
            sb.append("\n");
        }

        // Split relations into direct vs transitive
        List<ClassRelation> direct     = new java.util.ArrayList<>();
        List<ClassRelation> transitive = new java.util.ArrayList<>();
        for (ClassRelation rel : relations) {
            if (rel.mappings().isEmpty()) continue;  // pure inheritance, rendered in inheritance section
            boolean isDerived = rel.mappings().stream()
                    .allMatch(m -> m.mode() == MappingMode.TRANSITIVE_CLOSURE);
            (isDerived ? transitive : direct).add(rel);
        }

        // --- Direct lineage grouped by target class ---
        sb.append("## 字段血缘明细\n\n");
        renderGroupedTable(sb, direct, false);

        // --- Derived (transitive) lineage ---
        if (!transitive.isEmpty()) {
            sb.append("## 推导关联（传递性闭包）\n\n");
            sb.append("> 以下关联由工具自动推导，非源码直接体现。\n\n");
            renderGroupedTable(sb, transitive, true);
        }

        return sb.toString();
    }

    private void renderGroupedTable(StringBuilder sb, List<ClassRelation> rels, boolean derived) {
        Map<String, List<FieldMapping>> byTarget = new LinkedHashMap<>();
        for (ClassRelation rel : rels) {
            byTarget.computeIfAbsent(rel.targetClass(), k -> new java.util.ArrayList<>())
                    .addAll(rel.mappings());
        }
        for (Map.Entry<String, List<FieldMapping>> entry : byTarget.entrySet()) {
            String displayName = org.example.util.ClassNameValidator.extractSimpleName(entry.getKey());
            sb.append("### ").append(displayName).append("\n\n");
            if (derived) {
                sb.append("| 目标表字段 | 源表字段集合 | 推导路径 |\n");
                sb.append("|---|---|---|\n");
            } else {
                sb.append("| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |\n");
                sb.append("|---|---|---|---|---|---|\n");
            }
            for (FieldMapping m : entry.getValue()) {
                String sink    = formatSide(m.rightSide());
                String source  = formatSide(m.leftSide());
                String rawExpr = escape(m.rawExpression());
                if (derived) {
                    sb.append("| ").append(sink)
                      .append(" | ").append(source)
                      .append(" | *").append(rawExpr).append("* |\n");
                } else {
                    String type = m.type().name();
                    String mode = m.mode() == MappingMode.WRITE_ASSIGNMENT ? "WRITE" : "READ";
                    String loc  = m.location();
                    String norm = m.normalization() != null && !m.normalization().isEmpty()
                            ? String.join(", ", m.normalization())
                            : "";
                    sb.append("| ").append(sink)
                      .append(" | ").append(source)
                      .append(" | ").append(type)
                      .append(" | ").append(mode)
                      .append(" | `").append(loc).append("` |")
                      .append(norm.isEmpty() ? "" : " `" + norm + "` |")
                      .append("\n");
                    sb.append("| | *" + rawExpr + "* | | | |\n");
                }
            }
            sb.append("\n");
        }
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
