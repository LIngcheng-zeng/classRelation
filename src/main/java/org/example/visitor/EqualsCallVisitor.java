package org.example.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.analyzer.LocalAliasResolver;

import java.util.*;

/**
 * AST visitor that collects all .equals() call sites within a CompilationUnit.
 * Attaches the enclosing method's local alias map to each site.
 */
public class EqualsCallVisitor {

    /**
     * 遍历抽象语法树（AST），收集当前编译单元中所有的 equals() 调用点。
     *
     * <p>支持的检测模式：
     * <ol>
     *   <li>实例方法调用：{@code caller.equals(arg)} —— 最常见的对象比较形式</li>
     *   <li>静态工具方法：{@code Objects.equals(a, b)} —— Java 7+ 的空安全比较</li>
     * </ol>
     *
     * <p>处理流程：
     * <ul>
     *   <li>为每个方法构建局部别名映射表（用于追踪变量赋值关系，如 {@code String x = a.field; x.equals(...)}）</li>
     *   <li>递归遍历 AST 节点，识别所有名为 "equals" 的方法调用</li>
     *   <li>验证调用合法性：实例方法需有 scope 且参数数为 1；静态方法需属于 {@code java.util.Objects} 且参数数为 2</li>
     *   <li>封装调用点信息（caller、argument、完整表达式、代码位置、别名映射）并加入结果列表</li>
     * </ul>
     *
     * @param cu       待分析的编译单元（对应一个 .java 文件的 AST 根节点）
     * @param fileName 源文件名，用于构造代码位置字符串（格式："FileName.java:行号"）
     * @return 检测到的所有 equals() 调用点列表，按遍历顺序排列
     */
    public List<EqualCallSite> visit(CompilationUnit cu, String fileName) {
        List<EqualCallSite> sites = new ArrayList<>();

        new VoidVisitorAdapter<Map<String, Expression>>() {
            @Override
            public void visit(MethodDeclaration n, Map<String, Expression> ignored) {
                Map<String, Expression> aliasMap = LocalAliasResolver.resolve(n);
                super.visit(n, aliasMap);
            }

            @Override
            public void visit(LambdaExpr n, Map<String, Expression> aliasMap) {
                // Isolate lambda scope: remove entries that shadow lambda parameters
                Map<String, Expression> childMap = new HashMap<>(aliasMap != null ? aliasMap : Collections.emptyMap());
                n.getParameters().forEach(p -> childMap.remove(p.getNameAsString()));
                super.visit(n, childMap);
            }

            /**
             * 访问方法调用节点，识别并收集 equals() 调用点。
             *
             * <p>检测逻辑：
             * <ol>
             *   <li>过滤非 equals 方法调用</li>
             *   <li>提取代码位置信息（文件名:行号）</li>
             *   <li>匹配两种调用模式：
             *     <ul>
             *       <li>实例方法：{@code caller.equals(arg)} —— 参数数为 1 且存在 scope</li>
             *       <li>静态方法：{@code Objects.equals(a, b)} —— 参数数为 2 且 scope 为 Objects 类</li>
             *     </ul>
             *   </li>
             *   <li>封装调用点信息并存入结果列表</li>
             * </ol>
             *
             * @param n         当前访问的方法调用表达式节点
             * @param aliasMap  局部变量别名映射表，用于追踪变量赋值关系（如 {@code String x = a.field} 中 x 指向 a.field）
             */
            @Override
            public void visit(MethodCallExpr n, Map<String, Expression> aliasMap) {
                super.visit(n, aliasMap);

                if (!"equals".equals(n.getNameAsString())) return;

                Map<String, Expression> map = aliasMap != null ? aliasMap : Collections.emptyMap();
                String location = fileName + ":"
                        + n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");

                if (n.getArguments().size() == 1 && n.getScope().isPresent()) {
                    // Pattern: caller.equals(arg)
                    sites.add(new EqualCallSite(
                            n.getScope().get(), n.getArgument(0), n, location, map));

                } else if (n.getArguments().size() == 2 && isObjectsScope(n)) {
                    // Pattern: Objects.equals(a, b)
                    sites.add(new EqualCallSite(
                            n.getArgument(0), n.getArgument(1), n, location, map));
                }
            }

            private boolean isObjectsScope(MethodCallExpr n) {
                return n.getScope()
                        .filter(s -> s instanceof NameExpr ne
                                && ("Objects".equals(ne.getNameAsString())
                                    || "java.util.Objects".equals(ne.getNameAsString())))
                        .isPresent();
            }
        }.visit(cu, Collections.emptyMap());
        return sites;
    }
}
