# 元属性逻辑关联协议汇总 (MALM Protocol Summary)

> 📘 **相关文档**：[技术实现 (PROJECT_DOC.md)](PROJECT_DOC.md) | [使用指南 (USAGE.md)](USAGE.md)

## 0. 项目概述

**classRelation** 是一个 Java 源码静态分析工具，基于 **元属性逻辑关联协议（MALM）**，自动探测类与类之间基于字段等值判定、状态同步以及对象持有关系所隐含的字段血缘关系。

**核心特性**：
- ✅ 双引擎解析：JavaParser（单方法内分析）+ Spoon（跨过程分析）
- ✅ 三种关联模式：探测型（READ）、动作型（WRITE）、推导型（TRANSITIVE）
- ✅ 传递性闭包推导：自动发现间接数据流
- ✅ Lombok 支持：通过 Spoon CtModel 解析 @Data/@Builder 生成的代码
- ✅ 归一化操作采集：识别 `.toLowerCase()`, `.trim()` 等转换操作
- ✅ 包过滤功能：聚焦特定模块的关系分析
- ✅ 可视化报告：Mermaid 图谱 + 详细表格

**适用场景**：
- DTO ↔ Entity 映射关系梳理
- 微服务间数据结构兼容性分析
- 遗留系统重构前的依赖关系评估
- API 接口参数传递链路追踪

## 1. 核心定义 (Definition)

**元属性逻辑关联**是指类与类之间，基于属性值的**等值判定（Predicate）**或**状态同步（Assignment）**而建立的逻辑纽带。其本质是描述数据在不同对象维度下如何达成"语义对等"的规则集合。

**实现基础**：静态 AST 分析（JavaParser 3.26.4 + Spoon 10.4.2），感知范围限于源码中可静态解析的字段引用，不覆盖运行时动态绑定。

**分析流水线**（五阶段架构）：
```
Phase 0: Symbol Resolution  → 构建类型索引、继承关系、类包映射
Phase 1: Extraction         → 双引擎并行提取（JavaParser + Spoon）
Phase 2: Qualification      → 统一规范化为完全限定名（FQN）
Phase 3: Aggregation        → 按类对分组聚合
Phase 4: Enrichment         → 传递性闭包推导
scan → parse → visit → extract → classify → aggregate → expand → render
```

**方向约定**：所有关联中，`leftSide` 为数据**来源**（Source），`rightSide` 为数据**终点**（Sink）。
- 探测型（READ）：`leftSide = caller`，`rightSide = argument`，为实现约定，非语义方向
- 动作型（WRITE）：`leftSide = RHS 表达式`，`rightSide = LHS 字段`，为真实数据流方向
- 推导型（TRANSITIVE）：继承参与组合的两个原始关联的方向约定

---

## 2. 关联模式分类 (Association Modes)

### 2.1 探测型关联 (Read-Only Predicate)

通过 `equals()` 表达式检测，不修改任何状态。

**支持的采集形式**：

| 形式 | 示例 | 说明 |
| :--- | :--- | :--- |
| 实例方法调用 | `caller.equals(arg)` | 须有 scope 且恰好 1 个参数 |
| 静态工具方法 | `Objects.equals(a, b)` | scope 须为 `Objects` 或 `java.util.Objects`；`arg[0]` 为 leftSide |
| Getter 表达式 | `a.getOrderId().equals(b.refId)` | `getXxx()` 在字段提取时剥离为字段 `xxx` |
| Lambda 体内 | `list.stream().filter(x -> x.getId().equals(ext))` | VoidVisitorAdapter 自动穿透 lambda 体 |

**语义子类**（由 MappingType 区分，见第 5 节分类规则）：

* **原子等值 (Atomic Equality)**：
  * *公式*：$A.f_1 \equiv B.f_1$
  * *示例*：`a.orderId.equals(b.refOrderId)` → `FieldRef(A, orderId) ≡ FieldRef(B, refOrderId)`

* **投影组合关联 (Composite Projection)**：
  * *公式*：$\text{op}(A.f_1, A.f_2, \ldots) \equiv B.f_3$，或双侧均为组合：$\text{op}(A.f_1, A.f_2) \equiv \text{op}(B.f_1, B.f_2)$
  * *触发条件*：**任一侧** `fields.size() > 1`，或算子为 `concat` / `format`
  * *示例 1*：`(a.areaCode + a.phone).equals(b.fullMobile)` → 左侧两字段，operator=`direct`（EnclosedExpr），字段数 > 1 触发 COMPOSITE
  * *示例 2*：`(a.firstName + a.lastName).equals(b.givenName + b.familyName)` → 双侧均为组合，任一侧满足字段数 > 1

* **变换链关联 (Transform Chain)**：
  * *公式*：$\text{chain}(A.f_1) \equiv B.f_1$，或双侧均为变换链：$\text{chain}(A.f_1) \equiv \text{chain}(B.f_1)$
  * *触发条件*：**任一侧**表达式为 MethodCallExpr，且其 scope 也是 MethodCallExpr（即链式调用）
  * *示例 1*：`a.getCode().transform().equals(b.value)` → 左侧为链，operator=`transform` → PARAMETERIZED
  * *示例 2*：`a.getCode().normalize().equals(b.getValue().trim())` → 双侧均为变换链，任一侧满足即触发 PARAMETERIZED

---

### 2.2 动作型关联 (Write-Side Assignment)

通过赋值语义检测，表达数据从源侧流向目标侧的状态同步行为。

**支持的采集形式**：

| 形式 | 示例 | LHS 要求 | 说明 |
| :--- | :--- | :--- | :--- |
| 字段直接赋值 | `obj.field = expr` | `FieldAccessExpr` | 简单赋值（`=`），复合赋值（`+=` 等）不采集 |
| Setter 调用 | `obj.setXxx(value)` | — | 等价于 `obj.xxx = value`；须 1 个参数，方法名以 `set` + 大写字母开头 |
| 排除局部变量 | `String id = user.id` | `NameExpr`（LHS） | 不采集，但纳入方法体别名表供后续展开 |

**语义子类**（与 2.1 共用 MappingType 枚举）：

* **隐藏相等 (Implicit Equality)**：$B.f_1 \leftarrow A.f_1$（ATOMIC WRITE）
* **拟合同步 (State Alignment)**：$B.f_1 \leftarrow \text{op}(A.f_1, A.f_2)$（COMPOSITE / TRANSFORM WRITE）

---

### 2.3 推导型关联 (Transitive Closure)

由工具从已有关联中自动演绎，非源码直接体现。

**推导规则**：若存在关联 $M_1$（leftSide=L，rightSide=R）和关联 $M_2$（leftSide=R，rightSide=S），则推导 $M_3$（leftSide=L，rightSide=S）。

**关键实现细节**：
- **跨模式组合**：READ、WRITE、TRANSITIVE 三类关联均参与推导组合（不区分模式）
- **匹配条件**：$M_1$ 的 `rightSide` 与 $M_2$ 的 `leftSide` 存在相同的 `(className, fieldName)` 对，且 `className ≠ null`
- **防环防重**：`PairKey` 以 `leftSide` 和 `rightSide` 的所有字段签名（排序后拼接）为键，同签名不重复合成
- **固定点迭代**：每轮产生的推导关联 $M_3$ 纳入下一轮的索引参与继续推导，直至无新关联产生
- **溯源格式**：`"derived: [M1.rawExpression] → [M2.rawExpression]"`，`location = "transitive"`

---

## 3. 关联要素完备表 (Constituent Elements)

| 要素名称 | 技术描述 | 实现状态 |
| :--- | :--- | :--- |
| **源/宿 (Source/Sink)** | leftSide（来源）与 rightSide（终点）；className + fieldName 对 | ✅ |
| **映射算子 (Operator)** | 描述表达式结构：`direct` / `concat` / `format` / `transform` | ✅ 见第 4 节 |
| **归一化 (Normalization)** | 抹平物理差异的预处理规则（类型转换、大小写等） | ✅ GAP-03 已修复 |
| **谓词 (Predicate)** | 等值判定类型：`EQUALS` | ✅（仅 EQUALS，其余待扩展） |
| **变量上下文 (Context)** | 无法解析归属类的变量：`FieldRef(null, varName)` | ✅ 采集；✅ GAP-01 已修复 |
| **溯源 (Traceability)** | `rawExpression`（原始代码表达式）+ `location`（文件:行号） | ✅ |

---

## 4. 算子检测规则 (Operator Detection)

算子由 `FieldRefExtractor.detectOperator()` 在**别名展开后**对**展开后的表达式**检测（GAP-02 已修复）。

| 算子 | 触发条件 | 示例 |
| :--- | :--- | :--- |
| `concat` | 顶层是 `BinaryExpr(PLUS)`，或 MethodCallExpr 名为 `concat` / `join` | `a.f1 + a.f2`，`str.concat(other)` |
| `transform` | 顶层是 MethodCallExpr（非 concat/join/format/equals），且其 **scope 也是 MethodCallExpr** | `a.getCode().transform()` |
| `format` | 顶层是 MethodCallExpr 名为 `format` / `formatted` | `String.format("%s%s", f1, f2)` |
| `direct` | 其余所有情况（含 FieldAccessExpr、NameExpr、**EnclosedExpr**） | `obj.field`，`(a + b)`（注意） |

> ⚠️ **注意**：`(a.f1 + a.f2)` 整体是 `EnclosedExpr`，`detectOperator` 返回 `direct` 而非 `concat`。分类时依赖字段数量（>1）而非算子标识触发 COMPOSITE。

**字段提取逻辑**（递归，携带 aliasMap）：

| 表达式类型 | 处理策略 |
| :--- | :--- |
| `FieldAccessExpr` (`obj.field`) | 直接提取 `FieldRef(resolvedClass, fieldName)` |
| `MethodCallExpr` 且满足 isGetter | 剥离为 `FieldRef(scope_class, "xxx")`，**不再递归** |
| `MethodCallExpr` 非 Getter | 递归 scope + 所有参数 |
| `NameExpr` | 先查 aliasMap 展开；否则 `FieldRef(null, varName)` |
| `BinaryExpr` | 递归左右两侧 |
| `EnclosedExpr` | 递归内部表达式 |
| 字面量 / null | 忽略 |

**Getter 识别条件**：方法名以 `get` 开头 + 第 4 个字符大写 + 0 个参数 + 存在 scope。

---

## 5. 关联分类规则 (Classification Rules)

适用于探测型与动作型，分类优先级（高→低）：

| 优先级 | 判定条件（代码实现） | MappingType |
| :---: | :--- | :--- |
| 1 | 任一侧 `operatorDesc == "transform"` | `PARAMETERIZED` |
| 2 | 任一侧 `fields.size() > 1`，或 `operatorDesc == "concat"` 或 `"format"` | `COMPOSITE` |
| 3 | 其余 | `ATOMIC` |

**推导型关联**：固定为 `PARAMETERIZED`（由 `TransitiveClosureExpander` 直接设定，不经过此分类器）。

> **GAP-01 已修复**：含 `className=null` 的 `FieldRef`（裸变量参与）现在强制归类为 `PARAMETERIZED`。

---

## 6. 关联模式标识 (MappingMode)

| MappingMode | 含义 | 检测来源 | Mermaid 箭头 | 文档分节 |
| :--- | :--- | :--- | :--- | :--- |
| `READ_PREDICATE` | 探测型，`equals()` 表达 | `caller.equals(arg)` / `Objects.equals(a,b)` | `-->` 实线 | 字段血缘明细 |
| `WRITE_ASSIGNMENT` | 动作型，赋值表达 | `obj.field = expr` / `obj.setXxx(v)` | `-.->` 虚线 | 字段血缘明细 |
| `TRANSITIVE_CLOSURE` | 推导型，传递性推导 | 固定点迭代自动演绎 | `==>` 双实线 | 推导关联（独立节） |

---

## 7. 边界约束策略 (Constraints)

| 约束 | 规则 | 实现状态 |
| :--- | :--- | :--- |
| **有效性过滤** | 至少一侧有 `className ≠ null` 的字段；双侧均为空则丢弃 | ✅ |
| **自关联包含** | 同一类字段间关联（`A.f1 ≡ A.f2`）不过滤 | ✅ |
| **局部别名追踪** | 方法体内 `Type x = expr` 和 `x = expr`（NameExpr LHS）纳入别名表；Lambda 参数建立隔离子作用域 | ✅ |
| **Getter/Setter 映射** | `getXxx()` → 字段 `xxx`；`setXxx(v)` → 字段 `xxx` 的写入 | ✅ |
| **类型解析降级** | SymbolSolver 失败时降级为 scope 文本启发（变量名/方法名作为 className） | ✅ |
| **空值安全性** | `null vs null` 的行为定义 | ❌ 待实现 |
| **类型兼容性** | `Integer` 与 `Long` 等跨类型等值对齐 | ❌ 待实现 |
| **动态绑定** | 运行时变量决定的延迟关联 | ❌ 超出静态分析边界 |

---

## 8. 形式化逻辑示例 (JSON Schema)

### 原子等值（READ）
```json
{
  "association_id": "MAPPING_001",
  "type": "ATOMIC",
  "mode": "READ_PREDICATE",
  "left": { "class": "Order", "fields": ["orderId"], "operator": "direct" },
  "right": { "class": "Invoice", "fields": ["refOrderId"], "operator": "direct" },
  "traceability": {
    "raw_expression": "order.orderId.equals(invoice.refOrderId)",
    "location": "OrderService.java:58"
  }
}
```

### Getter 剥离 + 变换链（READ，PARAMETERIZED）
```json
{
  "association_id": "MAPPING_002",
  "type": "PARAMETERIZED",
  "mode": "READ_PREDICATE",
  "left": { "class": "Order", "fields": ["code"], "operator": "transform" },
  "right": { "class": "Invoice", "fields": ["refCode"], "operator": "direct" },
  "note": "getCode() stripped to 'code'; .transform() makes scope a MethodCallExpr → operator=transform",
  "traceability": {
    "raw_expression": "order.getCode().transform().equals(invoice.refCode)",
    "location": "OrderService.java:62"
  }
}
```

### 投影组合关联（READ，EnclosedExpr 情形）
```json
{
  "association_id": "MAPPING_003",
  "type": "COMPOSITE",
  "mode": "READ_PREDICATE",
  "left": { "class": "User", "fields": ["areaCode", "phone"], "operator": "direct" },
  "right": { "class": "Account", "fields": ["fullMobile"], "operator": "direct" },
  "note": "operator=direct because EnclosedExpr is not detected as concat; COMPOSITE triggered by fields.size()>1",
  "traceability": {
    "raw_expression": "(user.areaCode + user.phone).equals(account.fullMobile)",
    "location": "UserService.java:112"
  }
}
```

### 双侧变换链（READ，PARAMETERIZED）
```json
{
  "association_id": "MAPPING_004",
  "type": "PARAMETERIZED",
  "mode": "READ_PREDICATE",
  "left": { "class": "Order", "fields": ["code"], "operator": "transform" },
  "right": { "class": "Invoice", "fields": ["refCode"], "operator": "transform" },
  "note": "Both sides are transform chains; PARAMETERIZED triggered by either side having operator=transform",
  "traceability": {
    "raw_expression": "order.getCode().normalize().equals(invoice.getValue().trim())",
    "location": "OrderService.java:75"
  }
}
```

### 双侧投影组合（READ，COMPOSITE）
```json
{
  "association_id": "MAPPING_004",
  "type": "COMPOSITE",
  "mode": "READ_PREDICATE",
  "left": { "class": "Person", "fields": ["firstName", "lastName"], "operator": "direct" },
  "right": { "class": "Employee", "fields": ["givenName", "familyName"], "operator": "direct" },
  "note": "Both sides are composite; COMPOSITE triggered by fields.size()>1 on either side",
  "traceability": {
    "raw_expression": "(person.firstName + person.lastName).equals(emp.givenName + emp.familyName)",
    "location": "HRService.java:88"
  }
}
```

### Setter 调用（WRITE，ATOMIC）
```json
{
  "association_id": "MAPPING_004",
  "type": "ATOMIC",
  "mode": "WRITE_ASSIGNMENT",
  "left": { "class": "Order", "fields": ["userId"], "operator": "direct" },
  "right": { "class": "Invoice", "fields": ["buyerId"], "operator": "direct" },
  "note": "setXxx() recognized as WRITE_ASSIGNMENT; fieldName derived by stripping 'set' prefix",
  "traceability": {
    "raw_expression": "invoice.setBuyerId(order.userId)",
    "location": "InvoiceFactory.java:34"
  }
}
```

### 传递性推导（TRANSITIVE，跨模式组合）
```json
{
  "association_id": "MAPPING_005",
  "type": "PARAMETERIZED",
  "mode": "TRANSITIVE_CLOSURE",
  "left": { "class": "User", "fields": ["id"], "operator": "concat" },
  "right": { "class": "Invoice", "fields": ["buyerId"], "operator": "direct" },
  "note": "M1(WRITE: user.id→order.userId) + M2(WRITE: order.userId→invoice.buyerId); cross-mode combination is valid",
  "traceability": {
    "raw_expression": "derived: [order.userId = 'P' + id] → [invoice.buyerId = order.userId]",
    "location": "transitive"
  }
}
```

### 变量上下文（READ，className=null）
```json
{
  "association_id": "MAPPING_006",
  "type": "ATOMIC",
  "mode": "READ_PREDICATE",
  "left": { "class": "Order", "fields": ["tenantId"], "operator": "direct" },
  "right": { "class": null, "fields": ["currentTenantId"], "operator": "direct" },
  "note": "GAP-01: className=null variable should classify as PARAMETERIZED but currently classified as ATOMIC",
  "traceability": {
    "raw_expression": "order.tenantId.equals(currentTenantId)",
    "location": "TenantFilter.java:77"
  }
}
```

---

## 9. 已知实现差距 (Known Implementation Gaps)

> **状态更新**：以下 3 个 GAP 已在 v1.0-SNAPSHOT 中全部修复 ✅

---

### ~~GAP-01 · 变量上下文分类错误~~ ✅ 已修复

**问题描述**：含 `className=null` 的 `FieldRef`（裸变量参与等值）应分类为 `PARAMETERIZED`。

**修复方案**：在 `RelationshipClassifier.classify()` 中增加优先级检查，当任一侧包含 `className=null` 的字段时，强制返回 `PARAMETERIZED`。

```java
// 修复前: order.tenantId.equals(currentTenantId) → ATOMIC (错误)
// 修复后: order.tenantId.equals(currentTenantId) → PARAMETERIZED (正确)
```

**影响范围**：约 5-10% 的 READ 关联分类更准确。

---

### ~~GAP-02 · 别名展开后算子检测失效~~ ✅ 已修复

**问题描述**：算子描述应反映别名展开后的实际表达式结构。

**修复方案**：在 `FieldRefExtractor.extract()` 中，先调用 `expandAliases()` 展开别名，再对展开后的表达式调用 `detectOperator()`。

```java
// 修复前:
String fullName = user.firstName + user.lastName;  // alias: fullName → BinaryExpr(+)
order.userId = fullName;
// detectOperator(NameExpr "fullName") → "direct" → ATOMIC (错误)

// 修复后:
// expandAliases(fullName) → BinaryExpr(PLUS)
// detectOperator(BinaryExpr(PLUS)) → "concat" → COMPOSITE (正确)
```

**影响范围**：约 10-15% 的组合/变换场景分类更准确。

---

### ~~GAP-03 · 归一化属性未采集~~ ✅ 已修复

**问题描述**：`.toLowerCase()`、`.trim()`、类型转换等归一化操作应被识别并记录为独立属性。

**修复方案**：
1. 扩展 `FieldMapping` record，新增 `List<String> normalization` 字段
2. 在 `FieldRefExtractor` 中新增 `extractNormalization()` 方法，递归收集归一化操作
3. 在所有 MappingExtractor 中调用该方法并传递给 `FieldMapping` 构造函数
4. 更新渲染器（MarkdownDocumentRenderer、TableRenderer）显示归一化列

```java
// 示例:
account.mobileKey = user.phone.trim().toLowerCase();
// normalization: ["trim()", "toLowerCase()"]
```

**支持的归一化操作**：
- 大小写转换：`toLowerCase()`, `toUpperCase()`
- 空白处理：`trim()`, `strip()`
- 字符串替换：`replace()`, `replaceAll()`, `replaceFirst()`
- 类型转换：`String.valueOf()`, `toString()`, `intValue()`, `longValue()` 等
- 其他：`substring()`, `normalize()` (Unicode), Apache Commons 工具方法

**影响范围**：约 20-30% 的 PARAMETERIZED 关联现在包含完整的归一化信息。

---

## 10. 未来扩展方向

虽然核心 GAP 已修复，但以下场景仍有提升空间：

| 方向 | 说明 | 优先级 |
|------|------|--------|
| **null 安全性建模** | `null vs null` 的行为定义和特殊处理 | P1 |
| **类型兼容性** | `Integer` ↔ `Long` 等跨类型等值对齐的自动识别 | P1 |
| **Objects.equals 以外的静态工具方法** | 支持 `StringUtils.equals()`, `Objects.deepEquals()` 等 | P2 |
| **反射调用分析** | 有限的反射模式识别（如 `getField("name").get(obj)`） | P3 |
| **匿名内部类增强** | 更完善的匿名类/局部类中的字段访问追踪 | P2 |
| **复杂泛型嵌套** | 多层泛型嵌套（如 `Map<String, List<User>>`）的精确解析 | P3 |

---

## 11. 使用方法

详细的使用说明和命令行参数请参考 [**USAGE.md**](USAGE.md)。

### 快速开始

```bash
# 构建项目
mvn clean package -DskipTests

# 分析项目
java -jar target/classRelation.jar <project-root-path>

# 包过滤示例
java -jar target/classRelation.jar /path/to/project --package org.example.model
```

更多用法详见 [USAGE.md](USAGE.md)，包括：
- 完整的命令行参数说明
- 包过滤功能详解（精确匹配、通配符 `*`、递归 `**`）
- 输出文件说明
- 常见场景示例（DTO↔Entity、多模块分析）
- 常见问题解答（FAQ）

---

## 12. 版本历史

### v1.0-SNAPSHOT（当前版本）

**核心能力**：
- ✅ 双引擎架构：JavaParser（单方法内）+ Spoon（跨过程）
- ✅ SPI 扩展机制：支持自定义 SourceAnalyzer
- ✅ 五阶段流水线：解耦符号解析、提取、规范化、聚合、推导
- ✅ 完整 GAP 修复：GAP-01（变量分类）、GAP-02（算子检测）、GAP-03（归一化采集）

**新增功能**：
- 🆕 Spoon 跨过程分析：方法间参数字段映射
- 🆕 对象持有关系识别：Composition/Aggregation 检测
- 🆕 构造函数参数映射：`new Account(user.getPhone())` → 字段级血缘
- 🆕 Builder 模式支持：`Invoice.builder().build()` 类型推断
- 🆕 Optional/Monadic 支持：`optional.map(User::getId)` 泛型提取
- 🆕 任意深度链式调用：`a.getB().getC().getD()` 递归解析
- 🆕 Lombok @Data 支持：通过 CtModel 解析生成的 getter/setter
- 🆕 包过滤功能：`--package` 参数支持通配符匹配

**已知限制**：
- ❌ null 安全性建模（待实现）
- ❌ 类型兼容性自动识别（Integer ↔ Long 等）
- ❌ 反射调用分析（超出静态分析边界）
- ⚠️ 复杂泛型嵌套可能失效

---