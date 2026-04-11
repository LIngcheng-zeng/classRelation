package org.example.analyzer;

import org.example.model.ClassRelation;
import org.example.renderer.TableRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：使用内置测试样本验证完整分析流程。
 * 
 * 测试数据源：src/test/resources/test-projects/classRelationTestCode
 * （已纳入版本控制，不会因外部代码变化而失效）
 */
class LineageAnalyzerIntegrationTest {

    private LineageAnalyzer analyzer;
    private Path testProjectRoot;

    @BeforeEach
    void setUp() {
        analyzer = new LineageAnalyzer();
        
        // 从类路径加载测试项目（确保测试可重现）
        URL resource = getClass().getClassLoader()
                .getResource("test-projects/classRelationTestCode");
        
        if (resource != null) {
            try {
                testProjectRoot = Paths.get(resource.toURI());
            } catch (Exception e) {
                throw new IllegalStateException("无法加载测试项目", e);
            }
        }
        assertTrue(testProjectRoot.toFile().exists(), 
            "测试项目应该存在: " + testProjectRoot);
        assertTrue(testProjectRoot.toFile().isDirectory(),
            "测试项目应该是目录");
    }

    @Test
    void shouldRenderTable() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);
        System.out.println(new TableRenderer().render(relations));
    }

    @Test
    void shouldAnalyzeCompleteProject() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        assertNotNull(relations);
        assertFalse(relations.isEmpty(), "应该检测到类之间的关系");
        
        System.out.println("\n=== 检测到的类关系 ===");
        System.out.println("总数: " + relations.size());
        relations.forEach(rel -> {
            System.out.println("  " + rel.sourceClass() + " -> " + rel.targetClass() 
                + " (映射数: " + rel.mappings().size() + ")");
        });
    }

    @Test
    void shouldDetectOrderInvoiceRelations() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasOrderInvoiceRelation = relations.stream()
                .anyMatch(r -> 
                    (r.sourceClass().contains("Order") && r.targetClass().contains("Invoice")) ||
                    (r.sourceClass().contains("Invoice") && r.targetClass().contains("Order"))
                );
        
        assertTrue(hasOrderInvoiceRelation, 
            "应该检测到 Order 和 Invoice 之间的关系");
    }

    @Test
    void shouldDetectUserAccountRelations() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasUserAccountRelation = relations.stream()
                .anyMatch(r -> 
                    (r.sourceClass().contains("User") && r.targetClass().contains("Account")) ||
                    (r.sourceClass().contains("Account") && r.targetClass().contains("User"))
                );
        
        assertTrue(hasUserAccountRelation, 
            "应该检测到 User 和 Account 之间的关系");
    }

    @Test
    void shouldDetectCompositeMappings() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasCompositeMapping = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.type().name().equals("COMPOSITE"));
        
        assertTrue(hasCompositeMapping, 
            "应该检测到 COMPOSITE 类型的映射（String.format 拼接）");
    }

    @Test
    void shouldDetectWriteAssignments() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasWriteAssignment = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.mode().name().equals("WRITE_ASSIGNMENT"));
        
        assertTrue(hasWriteAssignment, 
            "应该检测到 WRITE_ASSIGNMENT 模式的映射");
    }

    @Test
    void shouldHandleInterProceduralAnalysis() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasInterProceduralRelation = relations.stream()
                .anyMatch(r -> 
                    r.mappings().stream()
                        .anyMatch(m -> m.location().contains("fillInvoice") || 
                                      m.location().contains("generateInvoice"))
                );
        
        assertTrue(hasInterProceduralRelation, 
            "应该通过跨过程分析检测到 fillInvoice/generateInvoice 中的字段映射");
    }

    @Test
    void shouldDetectBuilderPattern() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasBuilderPattern = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.rawExpression().contains("builder"));
        
        assertTrue(hasBuilderPattern, 
            "应该检测到 Address.builder() 构建器模式");
    }

    @Test
    void shouldDetectGetterChains() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasGetterChain = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> {
                    long dotCount = m.rawExpression().chars().filter(c -> c == '.').count();
                    return dotCount >= 3;
                });
        
        assertTrue(hasGetterChain, 
            "应该检测到长链式 getter 调用");
    }

    @Test
    void shouldDetectNormalizationOperations() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasNormalization = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.normalization() != null && !m.normalization().isEmpty());
        
        assertTrue(hasNormalization, 
            "应该检测到归一化操作（如 toLowerCase）");
    }

    @Test
    void shouldHaveValidClassNamesAfterEnrichment() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        long invalidRefs = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .flatMap(m -> {
                    java.util.stream.Stream<String> leftClasses = m.leftSide().fields().stream()
                            .map(f -> f.className());
                    java.util.stream.Stream<String> rightClasses = m.rightSide().fields().stream()
                            .map(f -> f.className());
                    return java.util.stream.Stream.concat(leftClasses, rightClasses);
                })
                .filter(className -> className != null && className.contains("("))
                .count();
        
        assertEquals(0, invalidRefs, 
            "所有字段引用应该有有效的类名");
    }

    @Test
    void shouldDetectSpecificFieldMappings() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Order.orderId -> Invoice.refOrderId
        boolean hasOrderIdMapping = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> 
                    m.leftSide().fields().stream().anyMatch(f -> 
                        "orderId".equals(f.fieldName())) &&
                    m.rightSide().fields().stream().anyMatch(f -> 
                        "refOrderId".equals(f.fieldName()))
                );
        assertTrue(hasOrderIdMapping, "应该检测到 orderId -> refOrderId 映射");

        // User.id/userId -> Invoice.buyerId
        boolean hasBuyerIdMapping = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> 
                    m.leftSide().fields().stream().anyMatch(f -> 
                        f.fieldName().equals("id") || f.fieldName().equals("userId")) &&
                    m.rightSide().fields().stream().anyMatch(f -> 
                        "buyerId".equals(f.fieldName()))
                );
        assertTrue(hasBuyerIdMapping, "应该检测到 userId/id -> buyerId 映射");
    }

    @Test
    void shouldHandleRecursiveMethodCalls() {
        assertDoesNotThrow(() -> analyzer.analyze(testProjectRoot),
            "分析不应因递归方法而失败");
    }

    @Test
    void shouldProduceMeaningfulResults() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        assertFalse(relations.isEmpty(), "不应该返回空结果");
        
        // 大部分关系应该有映射
        long relationsWithoutMappings = relations.stream()
                .filter(r -> r.mappings().isEmpty())
                .count();
        assertTrue(relationsWithoutMappings <= relations.size() * 0.1, 
            "大部分关系应该有映射");
        
        // 映射应该有位置信息
        long mappingsWithoutLocation = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .filter(m -> m.location() == null || m.location().isEmpty())
                .count();
        assertEquals(0, mappingsWithoutLocation, 
            "所有映射都应该有位置信息");
    }
}
