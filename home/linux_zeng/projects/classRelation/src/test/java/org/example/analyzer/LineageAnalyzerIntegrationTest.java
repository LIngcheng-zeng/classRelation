package org.example.analyzer;

import org.example.model.ClassRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：使用 classRelationTestCode 项目验证完整分析流程。
 */
class LineageAnalyzerIntegrationTest {

    private LineageAnalyzer analyzer;
    private Path testProjectRoot;

    @BeforeEach
    void setUp() {
        analyzer = new LineageAnalyzer();
        // 指向测试项目根目录
        testProjectRoot = Path.of("/home/linux_zeng/projects/classRelationTestCode");
    }

    @Test
    void shouldAnalyzeCompleteProject() {
        // When: 执行完整分析
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到关系
        assertNotNull(relations);
        assertFalse(relations.isEmpty(), "应该检测到类之间的关系");
        
        // 打印结果用于调试
        System.out.println("检测到的关系数量: " + relations.size());
        relations.forEach(rel -> {
            System.out.println("  " + rel.sourceClass() + " -> " + rel.targetClass() 
                + " (映射数: " + rel.mappings().size() + ")");
        });
    }

    @Test
    void shouldDetectOrderInvoiceRelations() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到 Order 和 Invoice 之间的关系
        boolean hasOrderInvoiceRelation = relations.stream()
                .anyMatch(r -> 
                    (r.sourceClass().contains("Order") && r.targetClass().contains("Invoice")) ||
                    (r.sourceClass().contains("Invoice") && r.targetClass().contains("Order"))
                );
        
        assertTrue(hasOrderInvoiceRelation, 
            "应该检测到 Order 和 Invoice 之间的关系（orderId/buyerId 等）");
    }

    @Test
    void shouldDetectUserAccountRelations() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到 User 和 Account 之间的关系
        boolean hasUserAccountRelation = relations.stream()
                .anyMatch(r -> 
                    (r.sourceClass().contains("User") && r.targetClass().contains("Account")) ||
                    (r.sourceClass().contains("Account") && r.targetClass().contains("User"))
                );
        
        assertTrue(hasUserAccountRelation, 
            "应该检测到 User 和 Account 之间的关系（phone/fullMobile 等）");
    }

    @Test
    void shouldDetectInheritanceRelations() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到 VipUser 继承 User 的关系
        boolean hasVipUserRelation = relations.stream()
                .anyMatch(r -> r.sourceClass().equals("VipUser"));
        
        assertTrue(hasVipUserRelation, 
            "应该检测到 VipUser 的继承关系");
    }

    @Test
    void shouldDetectCompositeMappings() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到组合映射（如 String.format 拼接）
        boolean hasCompositeMapping = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.type().name().equals("COMPOSITE"));
        
        assertTrue(hasCompositeMapping, 
            "应该检测到 COMPOSITE 类型的映射（如 isRightUser 中的 String.format）");
    }

    @Test
    void shouldDetectWriteAssignments() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到写赋值映射（如 fillInvoice 中的 setter 调用）
        boolean hasWriteAssignment = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.mode().name().equals("WRITE_ASSIGNMENT"));
        
        assertTrue(hasWriteAssignment, 
            "应该检测到 WRITE_ASSIGNMENT 模式的映射");
    }

    @Test
    void shouldHandleInterProceduralAnalysis() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该通过跨过程分析检测到 generateInvoice/fillInvoice 中的关系
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
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到 Address.builder() 模式
        boolean hasBuilderPattern = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.rawExpression().contains("builder"));
        
        assertTrue(hasBuilderPattern, 
            "应该检测到 Address.builder() 构建器模式");
    }

    @Test
    void shouldDetectGetterChains() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到 getter 链式调用（如 userOrderDTO.getOrderDTO().getOrder().getOrderId()）
        boolean hasGetterChain = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> {
                    long dotCount = m.rawExpression().chars().filter(c -> c == '.').count();
                    return dotCount >= 3; // 至少3个点表示链式调用
                });
        
        assertTrue(hasGetterChain, 
            "应该检测到长链式 getter 调用");
    }

    @Test
    void shouldDetectNormalizationOperations() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 应该检测到归一化操作（如 toLowerCase）
        boolean hasNormalization = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> m.normalization() != null && !m.normalization().isEmpty());
        
        assertTrue(hasNormalization, 
            "应该检测到归一化操作（如 toLowerCase、trim 等）");
    }

    @Test
    void shouldNotHaveNullClassNames() {
        // When
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // Then: 所有 FieldRef 应该有有效的类名（经过 TypeEnrichingDecorator 修复后）
        long invalidRefs = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .flatMap(m -> {
                    java.util.stream.Stream<String> leftClasses = m.leftSide().fields().stream()
                            .map(f -> f.className());
                    java.util.stream.Stream<String> rightClasses = m.rightSide().fields().stream()
                            .map(f -> f.className());
                    return java.util.stream.Stream.concat(leftClasses, rightClasses);
                })
                .filter(className -> className == null || className.contains("("))
                .count();
        
        assertEquals(0, invalidRefs, 
            "所有字段引用应该有有效的类名（不应包含 null 或方法调用噪声）");
    }
}
