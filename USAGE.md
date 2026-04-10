# classRelation - 使用指南

> 快速开始使用 classRelation 分析 Java 项目的字段血缘关系

---

## 📦 安装与构建

### 前置要求

- **JDK 21** 或更高版本
- **Maven 3.6+**（仅用于构建）

### 构建项目

```bash
mvn clean package -DskipTests
```

构建完成后，会在 `target/` 目录生成：
- `classRelation.jar` - 包含所有依赖的可执行 JAR

---

## 🚀 快速开始

### 基本用法

```bash
java -jar target/classRelation.jar <project-root-path>
```

**示例**：
```bash
java -jar target/classRelation.jar /home/user/my-project
```

执行后会：
1. 扫描指定目录下的所有 `.java` 文件
2. 分析类之间的字段映射关系
3. 生成报告文件：`<project-name>.md`

---

## 📋 命令行参数

### 语法

```bash
java -jar classRelation.jar <project-root-path> [OPTIONS]
```

### 可用选项

| 参数 | 简写 | 说明 | 示例 |
|------|------|------|------|
| `--package` | `-p` | 包过滤：只输出涉及指定包的关系 | `--package org.example.model` |
| `--strict` | - | 严格模式：只保留两端都在目标包内的关系 | 与 `--package` 配合使用 |

### 帮助信息

```bash
java -jar classRelation.jar
# 输出：
# Usage: java -jar classRelation.jar <project-root-path> [--package <pkg>] [--strict]
#   --package, -p <pkg>: Filter to show only relations involving specified package(s)
#                      Supports: com.example, com.example.*, com.example.**
#   --strict: Only include relations where BOTH source and target are in target packages
```

---

## 🎯 包过滤功能

当项目较大时，可以只关注特定包的类关系。

### 包模式匹配规则

| 模式 | 含义 | 匹配示例 | 不匹配示例 |
|------|------|----------|-----------|
| `com.example.model` | 精确匹配该包 | `com.example.model.User` | `com.example.dto.User` |
| `com.example.*` | 匹配直接子包 | `com.example.model`, `com.example.dto` | `com.example.model.vo` |
| `com.example.**` | 匹配所有子包 | `com.example.model`, `com.example.model.vo`, `com.example.service.impl` | - |

### 使用示例

#### 示例 1：单包过滤

```bash
# 只显示 org.example.model 包相关的关系
java -jar classRelation.jar /path/to/project --package org.example.model
```

**效果**：
- ✅ 保留：`User ↔ Order`（都在 model 包）
- ✅ 保留：`User ↔ UserService`（一端在 model 包）
- ❌ 排除：`UserService ↔ OrderService`（都不在 model 包）

#### 示例 2：一级子包通配符

```bash
# 显示 org.example 下所有一级子包的关系
java -jar classRelation.jar /path/to/project -p org.example.*
```

**效果**：
- ✅ 保留：`org.example.model.User`, `org.example.dto.OrderDTO`
- ❌ 排除：`org.example.model.vo.UserVO`（二级子包）

#### 示例 3：递归子包匹配

```bash
# 显示 org.example 及其所有子包的关系
java -jar classRelation.jar /path/to/project -p org.example.**
```

**效果**：
- ✅ 保留：所有 `org.example.*` 包下的类

#### 示例 4：严格模式

```bash
# 只保留两端都在 model 包内的关系
java -jar classRelation.jar /path/to/project --package org.example.model --strict
```

**效果**：
- ✅ 保留：`User ↔ Order`（两端都在 model 包）
- ❌ 排除：`User ↔ UserService`（只有一端在 model 包）

### 工作原理

1. **完整分析阶段**：扫描项目中所有 Java 文件，构建完整的字段映射关系图（包括跨包依赖）
2. **类名映射构建**：提取每个类的完整限定名（如 `org.example.model.User`）
3. **输出过滤阶段**：根据 `--package` 参数过滤关系
   - **默认模式（OR）**：`source ∈ target_pkg OR target ∈ target_pkg`
   - **严格模式（AND）**：`source ∈ target_pkg AND target ∈ target_pkg`

> 💡 **重要提示**：即使只输出特定包的关系，分析过程仍会扫描整个项目，确保不会遗漏通过其他包传递的数据流。

---

## 📄 输出文件

### 文件名规则

| 场景 | 输出文件 | 说明 |
|------|---------|------|
| 无过滤 | `<project-name>.md` | 完整报告 |
| 有过滤 | `<project-name>-filtered.md` | 过滤后的报告 |

### 报告内容结构

生成的 Markdown 报告包含以下部分：

1. **摘要统计**
   - 涉及的类关系对数量
   - 探测型关联（READ）数量
   - 动作型关联（WRITE）数量
   - 推导关联数量

2. **关联图谱（Mermaid）**
   - 可视化类之间的关系
   - 蓝色实线：READ 关系
   - 橙色虚线：WRITE 关系
   - 绿色虚线：继承关系

3. **关系类型说明**
   - AE（Atomic Equality）：原子等值
   - CP（Composite Projection）：投影组合
   - PD（Parameterized/Derived）：参数化/派生

4. **继承关系**（如检测到）
   - 子类 → 父类
   - 继承字段列表

5. **字段血缘明细**
   - 按目标类分节
   - 每个映射的详细信息：
     - 源字段、目标字段
     - 映射类型、模式
     - 代码位置
     - 归一化操作（如 `toLowerCase()`）

6. **推导关联**（传递性闭包）
   - 自动推导的间接关系
   - 推导路径展示

---

## 🔍 常见场景

### 场景 1：分析整个项目

```bash
java -jar classRelation.jar /path/to/project
# 输出：project.md
```

### 场景 2：只看 Model 层

```bash
java -jar classRelation.jar /path/to/project -p com.myapp.model
# 输出：project-filtered.md
```

### 场景 3：分析多个模块

```bash
# 分别分析
java -jar classRelation.jar /path/to/module-a
java -jar classRelation.jar /path/to/module-b

# 或者一起分析
java -jar classRelation.jar /path/to/parent-project
```

### 场景 4：查看 DTO 与 Entity 的映射

```bash
java -jar classRelation.jar /path/to/project -p com.myapp.dto
# 会显示 DTO 与其他包（如 entity、model）的关系
```

---

## ❓ 常见问题

### Q1: 为什么报告中的类名没有包前缀？

**A**: 为了保持报告简洁易读，ClassRelation 中只存储简单类名（如 `User` 而非 `com.example.model.User`）。包信息仅在过滤时使用。

### Q2: 包过滤会漏掉跨包的数据流吗？

**A**: 不会。分析阶段会扫描整个项目，构建完整的关系图。过滤只在输出阶段进行，确保不会遗漏通过其他包传递的间接关系。

### Q3: 如何理解 Mermaid 图中的不同颜色？

**A**: 
- 🔵 **蓝色实线**：READ_PREDICATE（探测型，如 `equals()`）
- 🟠 **橙色虚线**：WRITE_ASSIGNMENT（动作型，如赋值、setter）
- 🟢 **绿色虚线**：继承关系（extends）

### Q4: 推导关联是什么？

**A**: 推导关联是工具自动发现的间接关系。例如：
- 直接关系：`A.id → B.userId`，`B.userId → C.buyerId`
- 推导关系：`A.id → C.buyerId`（通过 B 传递）

### Q5: 支持 Lombok 吗？

**A**: 是的！通过 Spoon 引擎支持 Lombok 注解（@Data, @Builder, @Getter, @Setter 等）。

### Q6: 分析速度慢怎么办？

**A**: 
1. 使用包过滤减少输出量（但分析仍需扫描全部文件）
2. 排除测试代码：只指定 `src/main/java` 目录
3. 大型项目考虑增量分析（未来版本支持）

### Q7: 如何处理编译错误？

**A**: 工具会跳过无法解析的文件，继续分析其他文件。日志中会记录 WARNING 信息。

---

## 📚 相关文档

- 📘 [协议规范 (readeMe.md)](readeMe.md) - MALM 协议定义、关联模式分类
- 📗 [技术实现 (PROJECT_DOC.md)](PROJECT_DOC.md) - 架构设计、模块说明

---

## 🆘 获取帮助

遇到问题？请检查：
1. JDK 版本是否为 21+
2. 项目路径是否正确
3. 日志输出中的 WARNING/ERROR 信息

如需进一步帮助，请查阅技术文档或提交 Issue。
