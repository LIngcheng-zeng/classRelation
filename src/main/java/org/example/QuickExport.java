package org.example;

import org.example.analyzer.LineageAnalyzer;
import org.example.model.ClassRelation;
import org.example.renderer.AntVG6HtmlRenderer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 快速生成 JSON 数据和配置文件
 */
public class QuickExport {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 开始导出 JSON 数据 ===\n");
        
        // 分析项目
        LineageAnalyzer analyzer = new LineageAnalyzer();
        Path testPath = Paths.get("src/test/resources/test-projects/classRelationTestCode");
        
        System.out.println("分析路径: " + testPath.toAbsolutePath());
        List<ClassRelation> relations = analyzer.analyze(testPath);
        System.out.println("检测到 " + relations.size() + " 个类关系\n");
        
        // 创建渲染器
        AntVG6HtmlRenderer renderer = new AntVG6HtmlRenderer();
        
        // 输出目录
        File outputDir = new File("output");
        outputDir.mkdirs();
        
        // 1. 导出 JSON
        String jsonFile = new File(outputDir, "data.json").getAbsolutePath();
        renderer.saveAsJson(relations, jsonFile);
        System.out.println("✓ data.json (" + new File(jsonFile).length() + " bytes)");
        
        // 2. 生成配置
        String configFile = new File(outputDir, "datasource-config.json").getAbsolutePath();
        renderer.saveDataSourceConfig(
            "classRelation", 
            "data.json",
            relations.size() * 2,
            relations.size(),
            configFile
        );
        System.out.println("✓ datasource-config.json");
        
        // 3. 生成 HTML
        String htmlFile = new File(outputDir, "graph.html").getAbsolutePath();
        String html = renderer.renderWithExternalConfig("classRelation");
        java.nio.file.Files.writeString(Paths.get(htmlFile), html);
        System.out.println("✓ graph.html\n");
        
        System.out.println("文件位置: " + outputDir.getAbsolutePath());
        System.out.println("\n用浏览器打开 graph.html 即可查看图表");
    }
}
