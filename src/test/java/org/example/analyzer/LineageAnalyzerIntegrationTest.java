package org.example.analyzer;

import org.example.model.ClassRelation;
import org.example.renderer.AntVG6HtmlRenderer;
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
    void shouldExportJsonAndConfig() throws Exception {
        // 分析项目
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);
        assertFalse(relations.isEmpty(), "应该有类关系数据");
        
        // 创建渲染器
        AntVG6HtmlRenderer renderer = new AntVG6HtmlRenderer();
        
        // 导出 JSON 数据
        String json = renderer.exportJson(relations);
        assertNotNull(json);
        assertTrue(json.contains("\"nodes\""), "JSON 应包含 nodes 字段");
        assertTrue(json.contains("\"edges\""), "JSON 应包含 edges 字段");
        System.out.println("\n=== JSON 导出成功 ===");
        System.out.println("JSON 长度: " + json.length() + " 字符");
        
        // 生成配置文件
        int nodeCount = (int) json.chars().filter(c -> c == '{').count(); // 简单估算
        int edgeCount = relations.size();
        String config = renderer.generateDataSourceConfig(
            "TestProject", "data.json", nodeCount, edgeCount
        );
        assertNotNull(config);
        assertTrue(config.contains("\"dataSource\""), "配置应包含 dataSource");
        assertTrue(config.contains("\"metadata\""), "配置应包含 metadata");
        System.out.println("\n=== 配置文件生成成功 ===");
        System.out.println(config);
        
        // 保存文件到临时目录
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "graphify-test");
        tempDir.mkdirs();
        
        String jsonPath = new File(tempDir, "data.json").getAbsolutePath();
        String configPath = new File(tempDir, "datasource-config.json").getAbsolutePath();
        String htmlPath = new File(tempDir, "graph.html").getAbsolutePath();
        
        renderer.saveAsJson(relations, jsonPath);
        renderer.saveDataSourceConfig("TestProject", "data.json", nodeCount, edgeCount, configPath);
        
        // 生成 HTML（使用外部配置模式）
        String html = renderer.renderWithExternalConfig("TestProject");
        java.nio.file.Files.writeString(Paths.get(htmlPath), html);
        
        System.out.println("\n=== 文件已保存 ===");
        System.out.println("JSON: " + jsonPath);
        System.out.println("Config: " + configPath);
        System.out.println("HTML: " + htmlPath);
        
        // 验证文件存在
        assertTrue(new File(jsonPath).exists(), "JSON 文件应该存在");
        assertTrue(new File(configPath).exists(), "配置文件应该存在");
        assertTrue(new File(htmlPath).exists(), "HTML 文件应该存在");
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

    // ── MAP_JOIN 隐式等值断言 ─────────────────────────────────────────────────

    @Test
    void shouldDetectMapJoinImplicitEquality() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        boolean hasMapJoin = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name()));
        assertTrue(hasMapJoin, "应该检测到 MAP_JOIN 类型的隐式等值关系");
    }

    @Test
    void shouldDetectGroupingByBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // groupingBy(Staff::getDeptCode) + map.get(department.getDepartmentId())
        // → Department.departmentId (lookup arg) ≡ Staff.deptCode (map key)
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "departmentId".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "deptCode".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Staff.deptCode ≡ Department.departmentId（groupingBy 桥接）");
    }

    @Test
    void shouldDetectExplicitPutBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // map.put(product.getProductCode(), ...) + map.get(orderLine.getProductRef())
        // → OrderLine.productRef (lookup arg) ≡ Product.productCode (map key)
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "productRef".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "productCode".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Product.productCode ≡ OrderLine.productRef（显式 put 桥接）");
    }

    @Test
    void shouldDetectGetterAssignmentBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // String lookupKey = payment.getRefContractNo(); contractMap.get(lookupKey)
        // → Payment.refContractNo ≡ Contract.contractNo
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "refContractNo".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "contractNo".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Payment.refContractNo ≡ Contract.contractNo（getter 赋值变量桥接）");
    }

    @Test
    void shouldDetectStreamFilterEquality() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // filter(g -> g.getCatalogRef().equals(catalog.getCatalogCode()))
        // → Goods.catalogRef ≡ Catalog.catalogCode
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "catalogRef".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "catalogCode".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Goods.catalogRef ≡ Catalog.catalogCode（stream filter equals 桥接）");
    }

    @Test
    void shouldDetectDirectGetterBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // toMap(Supplier::getSupplierCode, ...) + map.get(purchaseOrder.getSupplierRef())
        // → Supplier.supplierCode ≡ PurchaseOrder.supplierRef
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "supplierRef".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "supplierCode".equals(f.fieldName())));
        assertTrue(found, "应该检测到 PurchaseOrder.supplierRef ≡ Supplier.supplierCode（直接 getter 桥接）");
    }

    // ── 跨文件 MAP_JOIN 断言 ──────────────────────────────────────────────────

    @Test
    void shouldDetectCrossFileFieldMapBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // orderIndexService.getOrderIndex().get(invoice.getRefOrderId())
        // OrderIndexService.orderIndex 由 toMap(Order::getOrderId, ...) 建立，通过 getter 跨类访问
        // → Invoice.refOrderId ≡ Order.orderId
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "refOrderId".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "orderId".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Invoice.refOrderId ≡ Order.orderId（跨文件实例字段 getter 桥接）");
    }

    @Test
    void shouldDetectCrossFileParameterMapBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // enterpriseMap.get(invoice.getBuyerId()) — Map<String, Enterprise> 作为参数传入
        // 泛型推断：key = String/#inferred；Invoice.buyerId 作为查找 key
        // → 产生 MAP_JOIN（Invoice.buyerId 一侧有具体来源，另一侧为 #inferred 哨兵）
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && (m.leftSide().fields().stream().anyMatch(f -> "buyerId".equals(f.fieldName()))
                         || m.rightSide().fields().stream().anyMatch(f -> "buyerId".equals(f.fieldName()))));
        assertTrue(found, "应该检测到包含 Invoice.buyerId 的 MAP_JOIN（跨文件参数 Map 泛型推断桥接）");
    }

    @Test
    void shouldDetectCrossFileStaticMapBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // BottomCacheHolder.BOTTOM_MAP.get(employee.getDepartmentCode())
        // BOTTOM_MAP 由 toMap(Bottom::getName, Bottom::getDescription) 建立
        // → Employee.departmentCode ≡ Bottom.name
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "departmentCode".equals(f.fieldName()))
                        && m.rightSide().fields().stream().anyMatch(f -> "name".equals(f.fieldName())));
        assertTrue(found, "应该检测到 Employee.departmentCode ≡ Bottom.name（跨文件静态字段 Map 桥接）");
    }

    @Test
    void shouldDetectCrossFileLambdaPutGetBridge() {
        List<ClassRelation> relations = analyzer.analyze(testProjectRoot);

        // CrossFileTest: forEach(item -> model1Map.put(item.getKey(), item.getName()))
        // CrossFileTest2: CrossFileTest.model1Map.get(crossFileModel2.getKey())
        // → CrossFileModel2.key ≡ CrossFileModel.key
        boolean found = relations.stream()
                .flatMap(r -> r.mappings().stream())
                .anyMatch(m -> "MAP_JOIN".equals(m.type().name())
                        && m.leftSide().fields().stream().anyMatch(f -> "key".equals(f.fieldName()) 
                            && ("CrossFileModel2".equals(f.className()) || "CrossFileModel".equals(f.className())))
                        && m.rightSide().fields().stream().anyMatch(f -> "key".equals(f.fieldName())
                            && ("CrossFileModel2".equals(f.className()) || "CrossFileModel".equals(f.className()))));
        assertTrue(found, "应该检测到 CrossFileModel2.key ≡ CrossFileModel.key（lambda 内 put + 跨文件静态字段 get 桥接）");
    }

    // ── 原有测试 ─────────────────────────────────────────────────────────────

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
