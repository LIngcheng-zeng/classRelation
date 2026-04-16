# 配置功能实现说明

## 已实现的功能

### 1. ✅ 通过配置文件配置包过滤规则

**配置文件**: `classrelation.properties`

```properties
# 包过滤规则（支持多个，逗号分隔）
package.filters=org.example.model,org.example.service

# 严格模式：false=OR逻辑（至少一边匹配），true=AND逻辑（两边都匹配）
package.strict.mode=false
```

**支持的包模式**:
- 精确匹配: `com.example.model`
- 单层通配符: `com.example.*` (匹配 com.example.model, com.example.dto)
- 子包匹配: `com.example.**` (递归匹配所有子包)

**优先级**: 命令行参数 > 配置文件 > 默认值

### 2. ✅ AntV G6 支持只渲染边最多的几个连通分量

**配置项**:
```properties
# 最大渲染的连通分量数量（0=全部渲染）
# 按边数从多到少选择
graph.max.components.to.render=5
```

**实现原理**:
1. 计算所有连通分量
2. 统计每个分量的边数
3. 按边数降序排序
4. 取前 N 个分量进行渲染

**优势**:
- 大幅减少大型项目的视觉混乱
- 聚焦于最核心的类关系
- 提升 HTML 渲染性能

### 3. ✅ 样例代码库位置支持在配置文件配置

**配置项**:
```properties
# 默认分析路径（命令行未提供时使用）
sample.code.path=/home/linux_zeng/projects/classRelationTestCode/src/main/java/org/example
```

**使用方式**:
```bash
# 无需提供项目路径，直接使用配置的 sample.code.path
java -jar classRelation.jar

# 命令行参数会覆盖配置
java -jar classRelation.jar /other/path
```

## 文件清单

### 新增文件

1. **src/main/java/org/example/config/AppConfig.java**
   - 配置管理类（单例模式）
   - 支持从三个位置加载配置
   - 提供类型安全的 getter 方法

2. **src/main/resources/classrelation.properties**
   - 默认配置文件模板
   - 包含详细的注释说明

3. **src/test/java/org/example/config/AppConfigTest.java**
   - AppConfig 单元测试
   - 测试各种配置场景

4. **CONFIGURATION.md**
   - 完整的配置使用指南
   - 包含示例和最佳实践

### 修改文件

1. **src/main/java/org/example/Main.java**
   - 集成 AppConfig
   - 支持从配置读取默认值
   - 命令行参数可覆盖配置
   - 传递 maxComponentsToRender 给渲染器

2. **src/main/java/org/example/renderer/AntVG6HtmlRenderer.java**
   - 新增 `render(projectName, relations, maxComponents)` 方法
   - 实现 `filterTopComponents()` 方法过滤连通分量
   - 优化性能：使用 HashMap 替代嵌套循环

## 使用方法

### 方法 1: 仅使用配置文件

创建 `classrelation.properties`:
```properties
package.filters=org.example.model,org.example.service
package.strict.mode=true
graph.max.components.to.render=3
sample.code.path=/path/to/sample/code
```

运行:
```bash
java -jar classRelation.jar
```

### 方法 2: 配置文件 + 命令行覆盖

配置文件设置默认值，命令行临时覆盖:
```bash
# 使用配置的 sample.code.path，但覆盖包过滤
java -jar classRelation.jar --package com.myapp.api

# 完全覆盖
java -jar classRelation.jar /custom/path --strict
```

### 方法 3: 自定义配置文件位置

```bash
java -Dconfig.path=/etc/myapp/classrelation.properties -jar classRelation.jar
```

## 技术细节

### 配置加载顺序

AppConfig 按以下顺序查找配置文件（找到即停止）:

1. 系统属性 `-Dconfig.path` 指定的路径
2. 当前目录 `./classrelation.properties`
3. classpath `/classrelation.properties` (打包在 JAR 中)

### 连通分量过滤算法

```java
1. 构建 nodeId → componentId 映射 (O(n))
2. 按 componentId 分组边 (O(e))
3. 按边数降序排序分量 (O(c log c), c=分量数)
4. 取前 N 个分量的 ID
5. 过滤节点和边 (O(n + e))
```

**时间复杂度**: O(n + e + c log c)，其中 n=节点数, e=边数, c=分量数

### 包过滤逻辑

**非严格模式 (OR)**:
```
include if (source ∈ targetPackages OR target ∈ targetPackages)
```

**严格模式 (AND)**:
```
include if (source ∈ targetPackages AND target ∈ targetPackages)
```

## 测试

运行单元测试:
```bash
mvn test -Dtest=AppConfigTest
```

完整测试:
```bash
mvn clean test
```

## 注意事项

1. **配置文件编码**: 必须使用 UTF-8 编码
2. **路径格式**: 支持绝对路径和相对路径
3. **数值验证**: 无效数值会自动回退到默认值并输出警告
4. **空值处理**: 空的包过滤列表表示不过滤（分析所有包）

## 后续优化建议

1. **性能优化**: 对于超大项目，可以考虑并行处理连通分量过滤
2. **配置验证**: 添加更严格的配置值验证和友好的错误提示
3. **动态重载**: 支持运行时重新加载配置（目前需要重启）
4. **YAML 支持**: 除了 properties 格式，也可以支持 YAML 配置
