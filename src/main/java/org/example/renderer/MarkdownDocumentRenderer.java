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
        // Sort by target class simple name (level-1), then aggregate
        List<ClassRelation> sorted = RelationDisplaySorter.sortByTargetClass(rels);
        Map<String, List<ClassRelation>> byTarget = new LinkedHashMap<>();
        for (ClassRelation rel : sorted) {
            byTarget.computeIfAbsent(rel.targetClass(), k -> new ArrayList<>()).add(rel);
        }

        for (Map.Entry<String, List<ClassRelation>> entry : byTarget.entrySet()) {
            String targetClass = entry.getKey();
            
            // Collect all mappings from relations targeting this class, excluding self-relations
            List<FieldMapping> fieldMappings = entry.getValue().stream()
                    .filter(rel -> !rel.sourceClass().equals(targetClass))  // Filter out self-relations
                    .flatMap(rel -> rel.mappings().stream())
                    .filter(m -> !isComposition(m))
                    .toList();
            
            if (fieldMappings.isEmpty()) continue;   // section has only composition or self-relations

            String displayName = ClassNameValidator.extractSimpleName(targetClass);
            sb.append("### ").append(displayName).append("\n\n");

            if (derived) {
                sb.append("| 目标字段 | 源端字段 | 代码位置 | 代码块 |\n");
                sb.append("|---|---|---|---|\n");
                renderDedupedRows(sb, fieldMappings, true);
            } else {
                sb.append("| 目标字段 | 源端字段 | 代码位置 | 代码块 |\n");
                sb.append("|---|---|---|---|\n");
                renderDedupedRows(sb, fieldMappings, false);
            }
            sb.append("\n");
        }
    }

    /**
     * Applies 4-level sort via {@link RelationDisplaySorter}, groups by sink field,
     * deduplicates (sinkField, sourceField) pairs, and renders rows with the
     * blank-continuation pattern for merged-cell simulation.
     *
     * Columns: 目标字段 | 源端字段 | 代码位置 | 代码块
     */
    private void renderDedupedRows(StringBuilder sb, List<FieldMapping> mappings, boolean derived) {
        List<FieldMapping> sorted = RelationDisplaySorter.sortMappings(mappings);

        // LinkedHashMap preserves the sort order produced above
        Map<String, List<FieldMapping>> bySink = new LinkedHashMap<>();
        for (FieldMapping m : sorted) {
            bySink.computeIfAbsent(formatSinkField(m.rightSide()), k -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<String, List<FieldMapping>> sinkEntry : bySink.entrySet()) {
            String sinkDisplay = sinkEntry.getKey();

            Map<String, FieldMapping> uniqueSources = new LinkedHashMap<>();
            for (FieldMapping m : sinkEntry.getValue()) {
                uniqueSources.putIfAbsent(dedupKey(m.leftSide()), m);
            }
            if (uniqueSources.isEmpty()) continue;

            boolean firstRow = true;
            for (FieldMapping m : uniqueSources.values()) {
                String sinkCell   = firstRow ? "`" + sinkDisplay + "`" : "";
                String sourceCell = formatSide(m.leftSide());
                String locCell    = "`" + m.location() + "`";
                String codeCell   = derived
                        ? "*" + escape(m.rawExpression()) + "*"
                        : escape(m.rawExpression());

                sb.append("| ").append(sinkCell)
                  .append(" | ").append(sourceCell)
                  .append(" | ").append(locCell)
                  .append(" | ").append(codeCell)
                  .append(" |\n");
                firstRow = false;
            }
        }
    }

    /** Source side: ClassName.fieldName (with backticks). */
    private String formatSide(org.example.model.ExpressionSide side) {
        if (side == null || side.isEmpty()) return "`<unknown>`";
        return String.join(", ", side.fields().stream()
                .map(f -> "`" + f.toString() + "`")
                .toList());
    }

    /** Sink side: field name only — class is shown in the section header. */
    private String formatSinkField(org.example.model.ExpressionSide side) {
        if (side == null || side.isEmpty()) return "<unknown>";
        return String.join(", ", side.fields().stream()
                .map(FieldRef::fieldName)
                .sorted()
                .toList());
    }

    /** Dedup key: simple class name + field name, to collapse FQN vs simple-name duplicates. */
    private String dedupKey(org.example.model.ExpressionSide side) {
        if (side == null || side.isEmpty()) return "<unknown>";
        return String.join(", ", side.fields().stream()
                .map(f -> ClassNameValidator.extractSimpleName(f.className()) + "." + f.fieldName())
                .toList());
    }

    private boolean isComposition(FieldMapping m) {
        return "composition-sink".equals(m.rightSide().operatorDesc());
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
