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
 * Algorithm: BFS wave propagation.
 *   Wave 0 = all base mappings.
 *   Each wave N derives new mappings by joining wave-N entries against the leftIndex.
 *   Only newly derived mappings form wave N+1 — already-processed pairs are never re-visited.
 *
 * Complexity: O(E × K) total, where E = unique (left,right) pairs and K = avg candidates per field key.
 * Previous fixed-point approach was O(N² × K × iterations) because it re-scanned ALL mappings each round.
 */
public class TransitiveClosureExpander {

    /**
     * 计算字段映射的传递闭包
     *
     * @param relations 原始类关系列表
     * @return 包含原始关系和派生关系的完整列表
     */
    public List<ClassRelation> expand(List<ClassRelation> relations) {
        // 步骤1：将所有 ClassRelation 中的 FieldMapping 展平到单一列表
        List<FieldMapping> all = new ArrayList<>();
        for (ClassRelation rel : relations) {
            all.addAll(rel.mappings());
        }

        // 步骤2：构建左索引，用于快速查找以特定字段为左侧的映射
        Map<FieldKey, List<FieldMapping>> leftIndex = buildLeftIndex(all);

        // 步骤3：初始化已见映射对集合，防止重复推导和循环依赖
        Set<PairKey> seenKeys = buildInitialSeenKeys(all);

        // 步骤4：存储所有派生的新映射
        List<FieldMapping> derived = new ArrayList<>();

        // 步骤5：BFS波传播 - 初始波包含所有基础映射
        // 每轮只处理新生成的映射，seenKeys确保每个(left,right)对最多处理一次
        List<FieldMapping> wave = new ArrayList<>(all);

        // 步骤6：执行BFS波传播循环
        while (!wave.isEmpty()) {
            // 下一波的映射容器
            List<FieldMapping> nextWave = new ArrayList<>();

            // 遍历当前波中的所有映射
            for (FieldMapping m1 : wave) {
                // 检查m1右侧的每个字段，寻找可以连接的映射
                for (FieldRef rfRight : m1.rightSide().fields()) {
                    // 跳过没有类名的字段引用
                    if (rfRight.className() == null) continue;
                    
                    // 构造查找键：使用m1右侧字段的类名和字段名
                    FieldKey key = new FieldKey(rfRight.className(), rfRight.fieldName());

                    // 在左索引中查找所有以该字段为左侧的映射m2
                    for (FieldMapping m2 : leftIndex.getOrDefault(key, List.of())) {
                        // 避免自连接
                        if (m2 == m1) continue;

                        // 构造新映射的唯一键：m1的左侧 → m2的右侧
                        PairKey pairKey = new PairKey(m1.leftSide(), m2.rightSide());
                        // 如果该映射对已存在，跳过（add返回false表示已存在）
                        if (!seenKeys.add(pairKey)) continue;

                        // 构建推导路径表达式，例如：User.id → Order.userId → Payment.orderId
                        String rawExpr = formatSide(m1.leftSide())
                                + " → " + formatSide(m1.rightSide())
                                + " → " + formatSide(m2.rightSide());
                        
                        // 创建新的传递闭包映射
                        FieldMapping m3 = new FieldMapping(
                                m1.leftSide(),                                    // 左侧继承自m1
                                m2.rightSide(),                                   // 右侧继承自m2
                                MappingType.PARAMETERIZED,                        // 映射类型：参数化
                                MappingMode.TRANSITIVE_CLOSURE,                   // 映射模式：传递闭包
                                rawExpr,                                          // 推导路径表达式
                                "transitive"                                      // 来源标记
                        );

                        // 将新映射加入下一波和结果集
                        nextWave.add(m3);
                        derived.add(m3);
                        // 将新映射加入左索引，供后续波次使用
                        indexMapping(m3, leftIndex);
                    }
                }
            }

            // 只有本轮新推导的映射进入下一轮处理
            wave = nextWave;
        }

        // 步骤7：如果没有派生任何新映射，直接返回原始关系
        if (derived.isEmpty()) return relations;

        // 步骤8：将派生的映射按目标类分组
        Map<String, List<FieldMapping>> derivedByTarget = new LinkedHashMap<>();
        for (FieldMapping m : derived) {
            // 提取右侧所有字段的类名（去重）
            List<String> targetClasses = m.rightSide().fields().stream()
                    .map(FieldRef::className)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            // 将映射添加到对应目标类的列表中
            for (String tgt : targetClasses) {
                derivedByTarget.computeIfAbsent(tgt, k -> new ArrayList<>()).add(m);
            }
        }

        // 步骤9：合并原始关系和派生关系
        List<ClassRelation> result = new ArrayList<>(relations);
        for (Map.Entry<String, List<FieldMapping>> entry : derivedByTarget.entrySet()) {
            // 创建标记为__derived__的派生类关系
            result.add(new ClassRelation("__derived__", entry.getKey(), entry.getValue()));
        }
        return result;
    }

    // -------------------------------------------------------------------------

    /**
     * 构建左索引：将字段映射按左侧字段进行分组索引
     * 用于快速查找以特定字段为左侧的所有映射
     *
     * @param mappings 字段映射列表
     * @return 字段键到映射列表的索引映射
     */
    private Map<FieldKey, List<FieldMapping>> buildLeftIndex(List<FieldMapping> mappings) {
        Map<FieldKey, List<FieldMapping>> index = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            indexMapping(m, index);
        }
        return index;
    }

    /**
     * 将单个字段映射添加到左索引中
     * 遍历映射左侧的所有字段，建立字段到映射的关联
     *
     * @param m 要索引的字段映射
     * @param index 左索引映射表
     */
    private void indexMapping(FieldMapping m, Map<FieldKey, List<FieldMapping>> index) {
        for (FieldRef rf : m.leftSide().fields()) {
            // 跳过没有类名的字段引用
            if (rf.className() == null) continue;
            // 将映射添加到对应字段键的列表中
            index.computeIfAbsent(new FieldKey(rf.className(), rf.fieldName()), k -> new ArrayList<>()).add(m);
        }
    }

    /**
     * 构建初始已见映射对集合
     * 将所有基础映射的(left, right)对加入集合，防止后续重复推导
     *
     * @param mappings 基础字段映射列表
     * @return 已见映射对的集合
     */
    private Set<PairKey> buildInitialSeenKeys(List<FieldMapping> mappings) {
        Set<PairKey> seen = new HashSet<>();
        for (FieldMapping m : mappings) {
            seen.add(new PairKey(m.leftSide(), m.rightSide()));
        }
        return seen;
    }

    // -------------------------------------------------------------------------

    /**
     * Formats an ExpressionSide for display in derivation paths.
     * Example: [User.id, User.phone] or Order.userId
     */
    private String formatSide(ExpressionSide side) {
        if (side == null || side.isEmpty()) return "<unknown>";
        List<String> fields = side.fields().stream()
                .map(FieldRef::toString)   // uses simple class name for readability
                .toList();
        return fields.size() == 1 ? fields.get(0) : "[" + String.join(", ", fields) + "]";
    }

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
