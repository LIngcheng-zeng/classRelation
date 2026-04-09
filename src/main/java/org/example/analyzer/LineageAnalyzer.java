package org.example.analyzer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.example.classifier.RelationshipClassifier;
import org.example.expander.TransitiveClosureExpander;
import org.example.graph.LineageGraph;
import org.example.model.ClassRelation;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import org.example.visitor.AssignmentSite;
import org.example.visitor.AssignmentVisitor;
import org.example.visitor.EqualCallSite;
import org.example.visitor.EqualsCallVisitor;
import org.example.visitor.SetterCallSite;
import org.example.visitor.SetterCallVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Orchestrates the full analysis pipeline:
 *   scan → parse → visit → extract → classify → aggregate
 */
public class LineageAnalyzer {

    private static final Logger log = Logger.getLogger(LineageAnalyzer.class.getName());

    private final JavaFileScanner          scanner         = new JavaFileScanner();
    private final EqualsCallVisitor        equalsVisitor   = new EqualsCallVisitor();
    private final AssignmentVisitor        assignVisitor   = new AssignmentVisitor();
    private final SetterCallVisitor        setterVisitor   = new SetterCallVisitor();
    private final FieldRefExtractor        extractor       = new FieldRefExtractor();
    private final RelationshipClassifier   classifier      = new RelationshipClassifier();
    private final TransitiveClosureExpander expander       = new TransitiveClosureExpander();

    /**
     * 执行完整的代码分析流程，扫描指定项目中的所有 Java 文件，提取类之间的字段血缘关系。
     *
     * <p>分析流程包含以下阶段：
     * <ol>
     *   <li>配置符号解析器以支持跨文件类型推导</li>
     *   <li>递归扫描项目目录，收集所有 .java 文件</li>
     *   <li>逐个解析文件为抽象语法树（AST）</li>
     *   <li>遍历 AST，识别 equals() 调用点和赋值语句</li>
     *   <li>提取字段引用并分类关系类型</li>
     *   <li>聚合所有映射关系，按类对组织结果</li>
     * </ol>
     *
     * <p>异常处理策略：单个文件解析失败不会中断整体分析，仅记录警告日志并继续处理其他文件。
     *
     * @param projectRoot 待分析项目的根目录路径，将递归扫描该目录下的所有 Java 源文件
     * @return 类关系列表，每个元素代表一对类之间的所有字段映射关系
     */
    public List<ClassRelation> analyze(Path projectRoot) {
        configureSymbolSolver(projectRoot);

        List<Path> javaFiles = scanner.scan(projectRoot);
        LineageGraph graph = new LineageGraph();

        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                String fileName = file.getFileName().toString();

                List<EqualCallSite> equalSites = equalsVisitor.visit(cu, fileName);
                for (EqualCallSite site : equalSites) {
                    processEqualsSite(site, graph);
                }

                List<AssignmentSite> assignSites = assignVisitor.visit(cu, fileName);
                for (AssignmentSite site : assignSites) {
                    processAssignmentSite(site, graph);
                }

                List<SetterCallSite> setterSites = setterVisitor.visit(cu, fileName);
                for (SetterCallSite site : setterSites) {
                    processSetterSite(site, graph);
                }
            } catch (IOException e) {
                log.warning("Failed to parse file: " + file + " — " + e.getMessage());
            } catch (Exception e) {
                log.warning("Unexpected error parsing: " + file + " — " + e.getMessage());
            }
        }

        List<ClassRelation> relations = graph.buildRelations();
        return expander.expand(relations);
    }

    /**
     * 处理单个 equals() 调用点，提取两侧字段引用并构建读取型映射关系。
     *
     * <p>处理流程：
     * <ol>
     *   <li>从 caller 和 argument 表达式中提取字段引用集合</li>
     *   <li>验证字段对的有效性（至少一侧包含可解析的类名）</li>
     *   <li>分类关系类型（ATOMIC / COMPOSITE / PARAMETERIZED）</li>
     *   <li>截断原始表达式以避免过长字符串</li>
     *   <li>将映射关系添加到血缘图中，标记为 READ_PREDICATE 模式</li>
     * </ol>
     *
     * @param site  equals() 调用点信息，包含 caller、argument、完整表达式和代码位置
     * @param graph 血缘图实例，用于聚合所有检测到的字段映射关系
     */
    private void processEqualsSite(EqualCallSite site, LineageGraph graph) {
        ExpressionSide leftSide  = extractor.extract(site.caller(),   site.aliasMap());
        ExpressionSide rightSide = extractor.extract(site.argument(), site.aliasMap());

        if (!isValidPair(leftSide, rightSide)) return;

        MappingType type = classifier.classify(leftSide, rightSide);
        String rawExpr = truncate(site.callExpr().toString());

        graph.addMapping(new FieldMapping(leftSide, rightSide, type, MappingMode.READ_PREDICATE, rawExpr, site.location()));
    }

    /**
     * 处理单个赋值语句，提取数据源和数据目标的字段引用并构建写入型映射关系。
     *
     * <p>处理流程：
     * <ol>
     *   <li>从赋值表达式右侧（RHS）提取数据源字段引用</li>
     *   <li>从赋值表达式左侧（LHS）提取数据目标字段引用</li>
     *   <li>验证字段对的有效性（至少一侧包含可解析的类名）</li>
     *   <li>分类关系类型（ATOMIC / COMPOSITE / PARAMETERIZED）</li>
     *   <li>截断原始表达式以避免过长字符串</li>
     *   <li>将映射关系添加到血缘图中，标记为 WRITE_ASSIGNMENT 模式</li>
     * </ol>
     *
     * <p>命名约定：sourceSide 表示数据来源（赋值号右侧的值），sinkSide 表示数据去向（赋值号左侧的目标）。
     *
     * @param site  赋值语句信息，包含 target（左值）、value（右值）、完整表达式和代码位置
     * @param graph 血缘图实例，用于聚合所有检测到的字段映射关系
     */
    private void processAssignmentSite(AssignmentSite site, LineageGraph graph) {
        // Convention: leftSide = data source (RHS value), rightSide = data sink (LHS target)
        ExpressionSide sourceSide = extractor.extract(site.value(),  site.aliasMap());
        ExpressionSide sinkSide   = extractor.extract(site.target(), site.aliasMap());

        if (!isValidPair(sourceSide, sinkSide)) return;

        MappingType type = classifier.classify(sourceSide, sinkSide);
        String rawExpr = truncate(site.assignExpr().toString());

        graph.addMapping(new FieldMapping(sourceSide, sinkSide, type, MappingMode.WRITE_ASSIGNMENT, rawExpr, site.location()));
    }

    private void processSetterSite(SetterCallSite site, LineageGraph graph) {
        // Sink: the field written by the setter — derive className from receiver scope
        ExpressionSide sourceSide = extractor.extract(site.value(), site.aliasMap());
        String sinkClassName = extractor.resolveClassNamePublic(site.receiverScope());
        ExpressionSide sinkSide = new ExpressionSide(
                List.of(new FieldRef(sinkClassName, site.fieldName())), "direct");

        if (!isValidPair(sourceSide, sinkSide)) return;

        MappingType type   = classifier.classify(sourceSide, sinkSide);
        String rawExpr     = truncate(site.call().toString());
        graph.addMapping(new FieldMapping(sourceSide, sinkSide, type, MappingMode.WRITE_ASSIGNMENT, rawExpr, site.location()));
    }

    /**
     * At least one side must have a resolved className, and not both sides empty.
     */
    private boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }

    private String truncate(String expr) {
        return expr.length() > 120 ? expr.substring(0, 117) + "..." : expr;
    }

    /**
     * 配置 JavaParser 的符号解析器（SymbolSolver），用于支持跨文件的类型推导和字段解析。
     *
     * <p>符号解析器的作用：
     * <ul>
     *   <li>解析变量和方法调用背后的真实类型（例如：推断 {@code user.getName()} 返回 {@code String}）</li>
     *   <li>支持跨文件引用解析（例如：A 类引用 B 类的字段时，能正确识别 B 类的结构）</li>
     *   <li>提升字段提取的准确性，减少因类型未知导致的分析失败</li>
     * </ul>
     *
     * <p>配置策略：
     * <ol>
     *   <li><b>ReflectionTypeSolver</b>：解析 JDK 标准库类型（如 {@code java.lang.String}、{@code java.util.List}）</li>
     *   <li><b>JavaParserTypeSolver</b>：解析项目源码中的自定义类型（基于传入的 {@code projectRoot} 目录）</li>
     * </ol>
     *
     * <p>降级容错机制：
     * 如果符号解析器初始化失败（例如项目路径无效、依赖缺失等），则回退到基础解析模式，
     * 仅使用词法分析和 AST 结构进行字段提取，此时类型推导能力受限，但不会中断整体分析流程。
     *
     * @param projectRoot 待分析项目的根目录路径，用于定位项目中的 Java 源文件
     */
    private void configureSymbolSolver(Path projectRoot) {
        try {
            // 创建组合类型求解器，支持多种类型来源
            CombinedTypeSolver solver = new CombinedTypeSolver();

            // 添加反射类型求解器：处理 JDK 内置类型（java.lang.*, java.util.* 等）
            solver.add(new ReflectionTypeSolver());

            // 添加 JavaParser 类型求解器：处理项目源码中的自定义类
            solver.add(new JavaParserTypeSolver(projectRoot));

            // 基于组合求解器构建 Java 符号解析器
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);

            // 配置 JavaParser 使用符号解析器
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(symbolSolver);
            StaticJavaParser.setConfiguration(config);
        } catch (Exception e) {
            // 符号解析器初始化失败时的降级处理
            log.warning("Symbol solver setup failed, type resolution will be degraded: " + e.getMessage());

            // 回退到基础解析模式：不使用符号解析，仅进行语法树解析
            // 此时字段类型推导将依赖词法启发式规则，准确性降低但不影响流程继续
            StaticJavaParser.setConfiguration(new ParserConfiguration());
        }
    }


}
