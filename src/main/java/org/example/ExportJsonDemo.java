package org.example;

import org.example.analyzer.LineageAnalyzer;
import org.example.model.ClassRelation;
import org.example.renderer.AntVG6HtmlRenderer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 演示如何导出 JSON 数据和配置文件。
 * 运行后会生成三个文件到 output 目录：
 * - data.json: 图数据
 * - datasource-config.json: 数据源配置
 * - graph.html: HTML 页面（从配置加载数据）
 */
public class ExportJsonDemo {
    
    public static void main(String[] args) throws Exception {
        // 1. 分析项目
        System.out.println("正在分析项目...");
        LineageAnalyzer analyzer = new LineageAnalyzer();
        
        // 使用测试项目路径
        Path testProjectPath = Paths.get("src/test/resources/test-projects/classRelationTestCode");
        if (!testProjectPath.toFile().exists()) {
            System.err.println("测试项目不存在: " + testProjectPath.toAbsolutePath());
            return;
        }
        
        List<ClassRelation> relations = analyzer.analyze(testProjectPath);
        System.out.println("检测到 " + relations.size() + " 个类关系\n");
        
        // 2. 创建渲染器
        AntVG6HtmlRenderer renderer = new AntVG6HtmlRenderer();
        
        // 3. 准备输出目录
        File outputDir = new File("output");
        outputDir.mkdirs();
        System.out.println("输出目录: " + outputDir.getAbsolutePath() + "\n");
        
        // 4. 导出 JSON 数据
        String jsonPath = new File(outputDir, "data.json").getAbsolutePath();
        System.out.println("正在导出 JSON 数据...");
        renderer.saveAsJson(relations, jsonPath);
        System.out.println("✓ JSON 数据已保存: " + jsonPath);
        
        // 5. 生成配置文件
        String configPath = new File(outputDir, "datasource-config.json").getAbsolutePath();
        System.out.println("正在生成配置文件...");
        renderer.saveDataSourceConfig(
            "classRelation Demo", 
            "data.json", 
            relations.size() * 2,  // 估算节点数
            relations.size()       // 边数
        , configPath);
        System.out.println("✓ 配置文件已保存: " + configPath);
        
        // 6. 生成 HTML（使用外部配置模式）
        String htmlPath = new File(outputDir, "graph.html").getAbsolutePath();
        System.out.println("正在生成 HTML 页面...");
        String html = renderer.renderWithExternalConfig("classRelation Demo");
        java.nio.file.Files.writeString(Paths.get(htmlPath), html);
        System.out.println("✓ HTML 页面已保存: " + htmlPath);
        
        // 7. 输出使用说明
        System.out.println("\n========================================");
        System.out.println("生成完成！文件位置：");
        System.out.println("========================================");
        System.out.println("1. 数据文件:   " + jsonPath);
        System.out.println("2. 配置文件:   " + configPath);
        System.out.println("3. HTML 页面:  " + htmlPath);
        System.out.println("\n使用方法：");
        System.out.println("  1. 将这三个文件放到同一个目录");
        System.out.println("  2. 用浏览器打开 graph.html");
        System.out.println("  3. 页面会自动从 datasource-config.json 读取配置");
        System.out.println("  4. 然后加载 data.json 显示图表");
        System.out.println("\n切换数据源：");
        System.out.println("  修改 datasource-config.json 中的 dataSource.path");
        System.out.println("  指向不同的 JSON 文件即可");
        System.out.println("========================================\n");
    }
}
