# classRelation — 项目技术文档

> 版本：1.0-SNAPSHOT | 语言：Java 21 | 核心依赖：JavaParser 3.26.4 + Spoon 10.4.2

> 📘 **相关文档**：[协议规范 (readeMe.md)](readeMe.md) | [使用指南 (USAGE.md)](USAGE.md)

---

## 1. 项目定位

`classRelation` 是一个 **Java 源码静态分析工具**，目标是自动探测项目中类与类之间基于字段等值判定（`equals()`）、状态同步（赋值）以及对象持有关系所隐含的字段血缘关系（Field Lineage），并支持传递性闭包推导。

其理论基础对应 `readeMe.md` 中定义的 **元属性逻辑关联协议（MALM）**，覆盖探测型、动作型、推导型三类关联模式，并扩展了跨过程分析和组合关系识别能力。

**核心价值**：
- 🔍 **数据流可视化**：将隐式的字段映射关系显式化，生成可读的血缘报告
- 🛠️ **重构辅助**：在系统重构前梳理类之间的依赖关系，评估影响范围
- 📊 **架构治理**：识别模块间的耦合度，发现不合理的数据传递链路
- ✅ **兼容性检查**：对比不同版本的 DTO/Entity 结构变化，评估 API 兼容性

**核心能力**：
- ✅ 字段等值映射（READ_PREDICATE）
- ✅ 字段赋值映射（WRITE_ASSIGNMENT）
- ✅ 传递性闭包推导（TRANSITIVE_CLOSURE）
- ✅ 跨过程字段血缘追踪（Inter-procedural Analysis）
- ✅ 对象持有/组合关系识别（Composition/Aggregation）
- ✅ 构造函数参数字段映射
- ✅ 任意深度链式调用解析
- ✅ 归一化操作采集（GAP-03 已修复）
- ✅ 别名展开后算子检测（GAP-02 已修复）
- ✅ 变量上下文正确分类（GAP-01 已修复）
- ✅ Lombok @Data/@Builder 支持

---

## 2. 分析流水线

### 2.1 五阶段架构

```
Phase 0: Symbol Resolution  → 构建类型索引、继承关系、类包映射
    │
    ▼
Phase 1: Extraction         → 双引擎并行提取（JavaParser + Spoon）
    │                           · JavaParserAnalyzer: 单方法内分析
    │                             - EqualsCallVisitor: caller.equals(arg)
    │                             - AssignmentVisitor: obj.field = expr
    │                             - SetterCallVisitor: obj.setXxx(value)
    │                           · SpoonAnalyzer: 跨过程分析
    │                             - CallProjectionExtractor: 方法间参数映射
    │                             - extractCompositionRelationships: 对象持有
    │                             - extractConstructorCalls: 构造函数映射
    │
    ▼
Phase 2: Qualification      → 统一规范化为完全限定名（FQN）
    │                           · FieldRefQualifier: 应用符号解析结果
    │                           · User-class Filter: 过滤非用户类
    │
    ▼
Phase 3: Aggregation        → 按类对分组聚合
    │                           · LineageGraph: Map<Src→Tgt, List<FieldMapping>>
    │                           · Inheritance Metadata: 附加继承信息
    │
    ▼
Phase 4: Enrichment         → 传递性闭包推导
                                · TransitiveClosureExpander: 固定点迭代
                                · PairKey 防环防重机制
```

### 2.2 执行流程示例

```java
// Main.java 入口
LineageAnalyzer analyzer = new LineageAnalyzer();  // 默认加载 JavaParser + Spoon
List<ClassRelation> relations = analyzer.analyze(projectRoot);

// 内部流程
// Phase 0: SymbolResolver.resolve() → SymbolResolutionResult
//   - fieldTypeMap: className.fieldName → Type
//   - inheritanceIndex: className → List<parentClassName>
//   - classPackageIndex: simpleName → FQN

// Phase 1: 双引擎提取
//   - JavaParserAnalyzer.analyze() → List<FieldMapping>
//     · 扫描所有 .java 文件
//     · 三路 Visitor 并行采集（equals / assign / setter）
//     · LocalAliasResolver 构建方法体别名表
//     · LambdaExpr 作用域隔离
//   - SpoonAnalyzer.analyze() → List<FieldMapping>
//     · 构建 CtModel（支持 Lombok）
//     · 提取方法间参数投影
//     · 识别对象持有关系
//     · 解析构造函数调用

// Phase 2: 规范化
//   - FieldRefQualifier.qualify() → List<FieldMapping>
//     · 将所有 FieldRef.className 转换为 FQN
//     · 应用 SymbolResolutionResult 的类型信息

// Phase 2.5: 用户类过滤
//   - 过滤掉任一侧不含用户类的映射
//   - userClassFqns = classPackageIndex.values()

// Phase 3: 聚合
//   - LineageGraph.addMapping() → Map<String, List<FieldMapping>>
//   - key = "SourceClass->TargetClass"

// Phase 4: 推导
//   - TransitiveClosureExpander.expand() → List<ClassRelation>
//     · 固定点迭代直至无新关联产生
//     · 生成 TRANSITIVE_CLOSURE 模式的映射
```

---

## 3. 核心模块说明

### 3.1 数据模型层 (`org.example.model`)

| 类型 | 职责 | 关键字段 |
|------|------|----------|
| `FieldRef` | 单个字段引用 | `className` (可为 null), `fieldName` |
| `ExpressionSide` | equals() 一侧的完整表达式 | `fields: List<FieldRef>`, `operatorDesc` |
| `MappingType` | 映射类型枚举 | `ATOMIC`, `COMPOSITE`, `PARAMETERIZED` |
| `MappingMode` | 关联模式枚举 | `READ_PREDICATE`, `WRITE_ASSIGNMENT`, `TRANSITIVE_CLOSURE` |
| `FieldMapping` | 一次关联的完整记录 | `leftSide`, `rightSide`, `type`, `mode`, `rawExpression`, `location`, `normalization` |
| `ClassRelation` | 两个类之间所有 FieldMapping 的聚合 | `sourceClass`, `targetClass`, `mappings: List<FieldMapping>` |

**数据流向**：`FieldRef` → `ExpressionSide` → `FieldMapping` → `ClassRelation`

**设计原则**：
- 不可变性：所有 record 均为 immutable
- 分层抽象：从原子字段引用到类关系的逐层聚合
- 溯源能力：每个 FieldMapping 保留原始代码表达式和位置信息

---

### 3.2 分析层 (`org.example.analyzer`)

#### `JavaFileScanner`
递归扫描目录，过滤 `.java` 文件，访问失败记录 WARNING 并继续。

#### `LocalAliasResolver`
扫描单个方法体，采集局部变量赋值（`VariableDeclarator` + `AssignExpr` LHS 为 NameExpr），构建 `Map<varName, Expression>`。用于后续 FieldRefExtractor 展开别名引用。

#### `FieldRefExtractor` ⭐ 核心
从任意表达式中提取字段引用集合：

| 模式 | 示例 | operator |
|---|---|---|
| 直接字段访问 | `obj.field` | `direct` |
| Getter 剥离 | `obj.getOrderId()` → `FieldRef(obj_type, "orderId")` | `direct` |
| 拼接组合 | `a.f1 + a.f2`、`.concat()`、`.join()` | `concat` |
| 格式化 | `String.format(...)` | `format` |
| 变换链 | `obj.getXxx().transform()` | `transform` |
| 别名展开 | `id`（aliasMap 中有 `id→user.id`）→ 递归展开 | 继承原始 |
| 裸变量 | `currentTenantId`（无别名）→ `FieldRef(null, "currentTenantId")` | `direct` |

**GAP-02 修复**：先展开别名再检测算子，确保 `String fullName = user.firstName + user.lastName; order.userId = fullName;` 正确识别为 COMPOSITE。

**GAP-03 归一化采集**：提取 `.toLowerCase()`, `.trim()`, `.replace()`, `String.valueOf()` 等归一化操作，记录在 `FieldMapping.normalization` 字段中。

类名解析：SymbolSolver 优先，失败降级为 scope 文本启发式。

#### `LineageAnalyzer` ⭐ 编排者
完整 Pipeline 的编排：
- SymbolSolver 初始化，失败时降级（不中断）
- 逐文件解析，异常隔离
- 三路 Visitor 并行采集（equals / assign / setter）
- 过滤：双侧均无字段引用 → 丢弃；至少一侧有 className → 保留
- 末尾调用 `TransitiveClosureExpander.expand()`

---

### 3.3 Spoon 跨过程分析层 (`org.example.analyzer.spoon`) ⭐ 新增

#### `SpoonAnalyzer`
基于 Spoon AST 的跨过程字段血缘分析，补充 JavaParser 的单方法内分析局限。

**核心能力**：
- **Lombok 支持**：通过 CtModel 解析 @Data 生成的 getter/setter
- **任意深度链式调用**：`a.getB().getC().getD()` 递归解析
- **Builder 模式识别**：`Invoice.builder().build()` 类型推断
- **Optional/Monadic 支持**：`optional.map(User::getId)` 泛型提取
- **继承层次搜索**：跨文件父类字段查找
- **构造函数参数映射**：`new Account(user.getPhone())` → `User.phone → Account.fullMobile`

#### `CallProjectionExtractor`
分析方法间参数传递的字段映射关系。

**工作流程**：
1. **extractCompositionRelationships**: 识别对象持有关系
   - `userOrderDTO.getOrderDTO()` → `UserOrderDTO → OrderDTO` (holds/held)
   - 只建立直接持有关系，不跳过中间层
   
2. **extractConstructorCalls**: 提取构造函数参数字段映射
   - `new Account(user.getPhone(), user.getId())`
   - 映射: `User.phone → Account.fullMobile`, `User.id → Account.userId`
   
3. **processInvocations**: 处理方法调用间的参数投影
   - 追踪 caller 参数到 callee 参数的字段传递
   - 递归展开深层调用链

**关键设计**：
- **直接关系原则**：只在实际参与字段映射的类之间建立关系
- **持有链分离**：组合关系单独记录，不与字段映射混淆
- **系统类过滤**：忽略 String、Integer 等 JDK 类的持有关系

---

### 3.4 访问者层 (`org.example.visitor`)

| 类 | 采集目标 | 携带信息 |
|---|---|---|
| `EqualsCallVisitor` | `caller.equals(arg)` + `Objects.equals(a,b)` | caller, argument, aliasMap |
| `AssignmentVisitor` | `obj.field = expr`（LHS 须为 FieldAccessExpr） | target, value, aliasMap |
| `SetterCallVisitor` | `obj.setXxx(value)`（1参数，名以 set+大写开头） | receiver, value, fieldName, aliasMap |

三个 Visitor 共同机制：
- **方法级别名表**：进入每个 `MethodDeclaration` 时调用 `LocalAliasResolver.resolve()` 构建 aliasMap
- **Lambda 作用域隔离**：进入 `LambdaExpr` 时创建子 aliasMap，移除与 lambda 参数同名的外层别名，防止污染

---

### 3.5 分类层 (`org.example.classifier`)

#### `RelationshipClassifier`
分类优先级（高→低）：

```
有 className=null 的字段      →  PARAMETERIZED  (GAP-01 修复)
有 transform 算子           →  PARAMETERIZED
字段数 > 1 或 concat/format  →  COMPOSITE
其余                         →  ATOMIC
```

**GAP-01 修复**：当任一侧包含 `className=null` 的 FieldRef（裸变量参与等值），强制归类为 PARAMETERIZED，因为这类映射依赖外部上下文。

推导型关联固定为 `PARAMETERIZED`（由 `TransitiveClosureExpander` 直接设定，不经过此分类器）。

---

### 3.6 图结构层 (`org.example.graph`)

#### `LineageGraph`
- 内部结构：`Map<"SrcClass->TgtClass", List<FieldMapping>>`
- 支持多字段跨类笛卡尔积（左侧 N 类 × 右侧 M 类）
- **包含自关联**（`A→A` 不过滤）

---

### 3.7 推导层 (`org.example.expander`)

#### `TransitiveClosureExpander`
固定点迭代实现完整传递闭包：

```
buildLeftIndex: FieldKey(className,fieldName) → List<FieldMapping>（按 leftSide 建索引）

fixedPoint:
  while 有新增:
    for M1 in allMappings:
      for rf in M1.rightSide（rf.className != null）:
        for M2 in leftIndex[rf]（M2 ≠ M1）:
          PairKey(M1.leftSide, M2.rightSide) 未见过 → 合成 M3
          M3.mode = TRANSITIVE_CLOSURE
          M3.raw  = "derived: [M1.raw] → [M2.raw]"
          加入 allMappings 和 leftIndex，seenKeys 记录防重防环
```

**防环机制**：`PairKey` 以 leftSide + rightSide FieldRef 集合的字符串签名为键，同签名不重复合成，天然截断循环路径。

输出：原始 `ClassRelation` 列表 + 推导关联列表（sourceClass = `__derived__`）。

---

### 3.8 渲染层 (`org.example.renderer`)

#### `MermaidRenderer`
| MappingMode | 箭头 | 标签前缀 |
|---|---|---|
| `READ_PREDICATE` | `-->` 实线 | `AE` / `CP` / `PD` |
| `WRITE_ASSIGNMENT` | `-.->` 虚线 | `AE` / `CP` / `PD` |
| `TRANSITIVE_CLOSURE` | `==>` 双实线 | `PD` |

#### `MarkdownDocumentRenderer`
生成完整 `.md` 报告，结构：
1. **摘要**：类关系对数、READ / WRITE / 传递闭包 计数
2. **关联图谱**：Mermaid 流程图 + 图例说明
3. **字段血缘明细**：按目标类分节，含原始代码表达式（溯源子行）+ **归一化操作列**
4. **推导关联**（独立节，仅当存在推导结果时输出）：列推导路径而非代码位置

**GAP-03 支持**：在表格中新增“归一化操作”列，显示 `toLowerCase()`, `trim()` 等操作。

#### `TableRenderer`
ASCII 表格（中文字符宽度补偿），含"模式"列（READ / WRITE）。

---

## 4. 入口与使用方式

### 构建
```bash
mvn package
# 生成 target/classRelation-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### 运行
```bash
java -jar target/classRelation-1.0-SNAPSHOT-jar-with-dependencies.jar <被分析项目根路径>
```

### 输出
在**当前工作目录**生成 `<projectName>.md`，文件名取自被分析项目的根目录名。

---

## 5. 当前能力边界

### ✅ 已实现能力

| 能力 | 支持情况 |
|---|---|
| `caller.equals(arg)` | ✅ |
| `Objects.equals(a, b)` 静态形式 | ✅ |
| Getter 语义剥离（`getXxx()` → 字段） | ✅ |
| Setter 语义识别（`setXxx(v)` → WRITE） | ✅ |
| 局部变量别名追踪（单方法体内） | ✅ |
| Lambda / Stream 穿透 | ✅（参数类型降级为变量名） |
| 传递性闭包推导 | ✅（完整闭包，防环） |
| 跨文件类型解析（SymbolSolver） | ✅（失败时降级） |
| 自关联（同类字段间） | ✅ |
| **跨过程字段映射** | ✅ **新增** (SpoonAnalyzer) |
| **对象持有/组合关系** | ✅ **新增** (Composition Detection) |
| **构造函数参数映射** | ✅ **新增** (Constructor Call Analysis) |
| **任意深度链式调用** | ✅ **新增** (Recursive Chain Resolution) |
| **Builder 模式识别** | ✅ **新增** (Builder Pattern Unwrapping) |
| **Optional/Monadic 支持** | ✅ **新增** (Generic Type Extraction) |
| **Lombok @Data 支持** | ✅ **新增** (CtModel Lookup) |
| **归一化操作采集** | ✅ **GAP-03 已修复** |
| **别名展开后算子检测** | ✅ **GAP-02 已修复** |
| **变量上下文正确分类** | ✅ **GAP-01 已修复** |

### ❌ 仍不支持的场景

| 能力 | 状态 | 说明 |
|---|---|---|
| `null` 安全性建模 | ❌ | 待实现 |
| 类型兼容性 | ❌ | `Integer` ↔ `Long` 等跨类型等值对齐 |
| 动态绑定 / 运行时变量 | ❌ | 超出静态分析边界 |
| `Objects.equals` 以外的静态工具方法 | ❌ | 未支持 |
| 反射调用 | ❌ | 静态分析局限 |
| 匿名内部类中的字段访问 | ⚠️ | 部分支持 |
| 泛型类型擦除后的复杂场景 | ⚠️ | 基础支持，复杂嵌套可能失效 |

---

## 6. 包结构一览

```
org.example
├── Main.java                          程序入口，命令行参数解析
├── spi/
│   └── SourceAnalyzer.java            SPI 接口，分析引擎契约
├── model/
│   ├── FieldRef.java                  字段引用 record
│   ├── ExpressionSide.java            表达式一侧 record
│   ├── FieldMapping.java              关联记录 (+normalization 字段, GAP-03)
│   ├── ClassRelation.java             类关系聚合 record
│   ├── MappingType.java               ATOMIC / COMPOSITE / PARAMETERIZED
│   └── MappingMode.java               READ / WRITE / TRANSITIVE
├── analyzer/
│   ├── LineageAnalyzer.java           五阶段流水线编排
│   ├── SymbolResolver.java            Phase 0: 符号解析
│   ├── FieldRefQualifier.java         Phase 2: FQN 规范化
│   ├── javaparser/                    JavaParser 引擎
│   │   ├── JavaParserAnalyzer.java    SourceAnalyzer 实现
│   │   ├── LocalAliasResolver.java    方法体别名表构建
│   │   └── FieldRefExtractor.java     字段引用提取 (+GAP-02/GAP-03)
│   └── spoon/                         Spoon 引擎 ⭐
│       ├── SpoonAnalyzer.java         SourceAnalyzer 实现
│       ├── SpoonResolutionHelper.java 类型解析辅助工具
│       ├── ExecutionContext.java      执行上下文封装
│       ├── ExpressionResolverChain.java 表达式解析链
│       └── intra/                     方法内分析
│           ├── CallProjectionExtractor.java  方法间映射 + 持有关系 + 构造函数
│           └── ConstructorCallExtractor.java 构造函数调用提取
├── visitor/                           JavaParser Visitor
│   ├── EqualsCallVisitor.java         equals() 采集 (+Objects.equals + Lambda 隔离)
│   ├── AssignmentVisitor.java         赋值采集 (+Lambda 隔离)
│   └── SetterCallVisitor.java         setter 采集
├── classifier/
│   └── RelationshipClassifier.java    分类器 (+GAP-01 变量上下文修复)
├── graph/
│   └── LineageGraph.java              聚合器 (包含自关联)
├── expander/
│   └── TransitiveClosureExpander.java 传递闭包固定点迭代
├── renderer/
│   ├── MermaidRenderer.java           Mermaid 图谱生成
│   ├── TableRenderer.java             ASCII 表格 (+模式列 + 归一化列)
│   └── MarkdownDocumentRenderer.java  Markdown 报告生成 (+归一化操作列)
└── util/
    └── PackageFilter.java             包过滤工具 (通配符匹配)
```

---

## 7. 架构设计亮点

### 7.1 SPI 扩展机制

通过 `SourceAnalyzer` 接口解耦分析引擎与编排逻辑：

```java
public interface SourceAnalyzer {
    List<FieldMapping> analyze(Path projectRoot, SymbolResolutionResult symbols);
}
```

**优势**：
- 第三方可实现自定义分析器（如 ASM 字节码分析、Kotlin 支持）
- 测试时可注入 Mock Analyzer
- 符合开闭原则，新增引擎无需修改 LineageAnalyzer

### 7.2 五阶段流水线

将分析流程拆分为五个独立阶段，每阶段职责单一：

| 阶段 | 输入 | 输出 | 可替换性 |
|------|------|------|----------|
| Phase 0: Symbol Resolution | Path | SymbolResolutionResult | ❌ 核心基础 |
| Phase 1: Extraction | Path + Symbols | List<FieldMapping> | ✅ 可插拔 (SPI) |
| Phase 2: Qualification | List<FieldMapping> | List<FieldMapping> | ⚠️ 可扩展 |
| Phase 3: Aggregation | List<FieldMapping> | List<ClassRelation> | ⚠️ 可扩展 |
| Phase 4: Enrichment | List<ClassRelation> | List<ClassRelation> | ✅ 可增强 |

**优势**：
- 各阶段独立测试
- Phase 1 可并行执行多个 Analyzer
- Phase 2 统一应用规范化规则，避免重复逻辑

### 7.3 防环防重机制

`TransitiveClosureExpander` 使用 `PairKey` 防止循环推导：

```java
// PairKey 以 leftSide + rightSide 的所有字段签名（排序后拼接）为键
String signature = leftFields.stream().sorted().collect(joining(","))
    + "->" + rightFields.stream().sorted().collect(joining(","));
```

**效果**：
- A→B + B→A 不会无限循环
- 相同签名的推导结果不重复生成
- 自然截断循环路径

### 7.4 降级策略

多处采用优雅降级，确保分析不因局部失败而中断：

1. **SymbolSolver 失败** → 降级为 scope 文本启发式
2. **文件解析失败** → 记录 WARNING，继续处理其他文件
3. **类型推断失败** → FieldRef.className = null，强制归类为 PARAMETERIZED

---
