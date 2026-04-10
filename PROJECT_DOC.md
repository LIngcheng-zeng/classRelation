# classRelation — 项目技术文档

> 版本：1.0-SNAPSHOT | 语言：Java 21 | 核心依赖：JavaParser 3.26.4 + Spoon 10.4.0

---

## 1. 项目定位

`classRelation` 是一个 **Java 源码静态分析工具**，目标是自动探测项目中类与类之间基于字段等值判定（`equals()`）、状态同步（赋值）以及对象持有关系所隐含的字段血缘关系（Field Lineage），并支持传递性闭包推导。

其理论基础对应 `readeMe.md` 中定义的 **元属性逻辑关联协议（MALM）**，覆盖探测型、动作型、推导型三类关联模式，并扩展了跨过程分析和组合关系识别能力。

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

---

## 2. 分析流水线

```
projectRoot (目录)
    │
    ▼
[JavaFileScanner]             递归扫描所有 .java 文件
    │
    ▼
[StaticJavaParser + Spoon]    双引擎解析：
    │                           · JavaParser: AST + SymbolSolver 类型推导
    │                           · Spoon: CtModel（支持 Lombok 生成的 getter）
    │
    ├──▶ [EqualsCallVisitor]  采集 caller.equals(arg) / Objects.equals(a,b)
    │         + LocalAliasResolver（方法体别名表）
    │         + LambdaExpr 作用域隔离
    │
    ├──▶ [AssignmentVisitor]  采集 obj.field = expr
    │         + LocalAliasResolver + LambdaExpr 隔离
    │
    ├──▶ [SetterCallVisitor]  采集 obj.setXxx(value) → 等价 WRITE_ASSIGNMENT
    │         + LocalAliasResolver + LambdaExpr 隔离
    │
    └──▶ [SpoonAnalyzer]      跨过程分析（新增）
              · CallProjectionExtractor: 方法间参数字段映射
              · extractCompositionRelationships: 对象持有关系
              · extractConstructorCalls: 构造函数参数映射
    │
    ▼
[FieldRefExtractor]           提取字段引用，支持：
    │  · 直接字段访问 obj.field
    │  · Getter 剥离 obj.getXxx() → field "xxx"
    │  · 拼接/格式化/变换链
    │  · 局部别名展开（aliasMap）
    │  · GAP-02: 别名展开后算子检测
    │  · GAP-03: 归一化操作采集
    │
    ▼
[RelationshipClassifier]      分类 ATOMIC / COMPOSITE / PARAMETERIZED
    │                           · GAP-01: 变量上下文正确分类
    ▼
[LineageGraph]                聚合为 ClassRelation（按 SrcClass→TgtClass 分组）
    │
    ▼
[TransitiveClosureExpander]   固定点迭代推导传递关联 A≡B + B≡C → A≡C
    │                         PairKey 防环防重，完整闭包无跳数限制
    ▼
[MarkdownDocumentRenderer]    生成 <projectName>.md 报告
    · 摘要统计
    · Mermaid 关联图谱（三种箭头）
    · 字段血缘明细（按目标类分节，含归一化操作列）
    · 推导关联（独立节）
```

---

## 3. 核心模块说明

### 3.1 数据模型层 (`org.example.model`)

| 类型 | 职责 |
|---|---|
| `FieldRef` | 单个字段引用：`className + fieldName`，className 可为 null |
| `ExpressionSide` | equals() 一侧的完整表达式：FieldRef 列表 + 算子（direct/concat/format/transform） |
| `MappingType` | `ATOMIC` / `COMPOSITE` / `PARAMETERIZED` |
| `MappingMode` | `READ_PREDICATE` / `WRITE_ASSIGNMENT` / `TRANSITIVE_CLOSURE` |
| `FieldMapping` | 一次关联的完整记录：leftSide、rightSide、type、**mode**、rawExpression、location |
| `ClassRelation` | 两个类之间所有 FieldMapping 的聚合：sourceClass → targetClass → mappings |

**数据流向**：`FieldRef` → `ExpressionSide` → `FieldMapping` → `ClassRelation`

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
├── Main.java
├── model/
│   ├── FieldRef.java
│   ├── ExpressionSide.java
│   ├── FieldMapping.java              +normalization 字段 (GAP-03)
│   ├── ClassRelation.java
│   ├── MappingType.java               ATOMIC / COMPOSITE / PARAMETERIZED
│   └── MappingMode.java               READ_PREDICATE / WRITE_ASSIGNMENT / TRANSITIVE_CLOSURE
├── analyzer/
│   ├── JavaFileScanner.java
│   ├── LocalAliasResolver.java        方法体局部变量别名表构建
│   ├── FieldRefExtractor.java         字段引用提取 + GAP-02/GAP-03 修复
│   ├── LineageAnalyzer.java           全流水线编排 (JavaParser)
│   └── spoon/                         ⭐ 新增 Spoon 分析器
│       ├── SpoonAnalyzer.java         跨过程分析入口
│       ├── SpoonAliasBuilder.java     Spoon 别名表构建
│       └── CallProjectionExtractor.java  方法间字段映射 + 持有关系 + 构造函数
├── visitor/
│   ├── EqualCallSite.java             +aliasMap 字段
│   ├── EqualsCallVisitor.java         +Objects.equals + Lambda 隔离
│   ├── AssignmentSite.java            +aliasMap 字段
│   ├── AssignmentVisitor.java         +Lambda 隔离
│   ├── SetterCallSite.java            Setter 调用点 record
│   └── SetterCallVisitor.java         obj.setXxx(v) 采集
├── classifier/
│   └── RelationshipClassifier.java    +GAP-01 变量上下文分类修复
├── graph/
│   └── LineageGraph.java              移除自关联过滤
├── expander/
│   └── TransitiveClosureExpander.java 传递闭包固定点迭代
└── renderer/
    ├── MermaidRenderer.java           三种箭头样式
    ├── TableRenderer.java             +模式列 + 归一化列
    └── MarkdownDocumentRenderer.java  +归一化操作列显示
```
