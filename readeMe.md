# 元属性逻辑关联协议汇总 (MALM Protocol Summary)

## 1. 核心定义 (Definition)
**元属性逻辑关联**是指类与类之间，基于属性值的**等值判定（Predicate）**或**状态同步（Assignment）**而建立的逻辑纽带。其本质是描述数据在不同对象维度下如何达成"语义对等"的规则集合。

> **实现范围说明**：本协议当前实现基于**静态 AST 分析**，感知范围限于源码中可静态解析的字段引用，不覆盖运行时动态绑定。分析入口为 `LineageAnalyzer`，流水线为 `scan → parse → visit → extract → classify → aggregate → render`。

---

## 2. 关联模式分类 (Association Modes)

### 2.1 探测型关联 (Read-Only Predicate)

通过 `caller.equals(arg)` 表达式检测，不修改任何状态。

* **原子等值 (Atomic Equality)**：类 $A$ 的单字段与类 $B$ 的单字段直接 `equals`。
    * *公式*：$A.f_1 \equiv B.f_1$
    * *代码示例*：`a.orderId.equals(b.userId)`
* **投影组合关联 (Composite Projection)**：类 $A$ 的多个字段经算子（如拼接、格式化）后，与类 $B$ 进行等值比较。
    * *公式*：$f(A.f_1, A.f_2, ...) \equiv g(B.f_3, B.f_4, ...)$
    * *代码示例*：`(a.areaCode + a.phone).equals(b.fullMobile)`
* **参数化动态关联 (Parametric/Variable)**：引入运行时变量（Variable）或方法链变换作为桥梁。
    * *公式*：$A.f_1 \equiv V_{context} \rightarrow B.f_{target}$
    * *代码示例*：`a.getCode().transform().equals(b.value)`
    * *变量策略*：表达式中出现的裸变量名视为参与等值关联，以变量名作为字段标识，类名标记为未知（`null`）。

### 2.2 动作型关联 (Write-Side Assignment)

通过赋值表达式 `target.field = value` 检测，表达数据从源流向目标的状态同步行为。

* **隐藏相等 (Implicit Equality)**：属性赋值本质上是强制制造"相等"状态。
    * *逻辑*：执行 $B.f_1 = A.f_1$ 后，建立瞬时强关联（ATOMIC WRITE）。
    * *代码示例*：`b.mobile = a.phone`
* **拟合同步 (State Alignment)**：通过计算将 $A$ 的状态映射给 $B$，确保后续逻辑中的 `equals` 成立。
    * *逻辑*：$B.f_1 = \text{compute}(A.f_1, A.f_2)$（COMPOSITE / PARAMETERIZED WRITE）
    * *代码示例*：`b.fullName = a.firstName + " " + a.lastName`

> **探测型采集规则**：支持两种形式：
> - `caller.equals(arg)` — 实例方法调用形式
> - `Objects.equals(a, b)` — `java.util.Objects` 静态两参数形式，`arg[0]` 为来源侧，`arg[1]` 为目标侧
>
> **动作型采集规则**：仅采集 LHS 为字段访问表达式（`obj.field`）的简单赋值（`=`）。裸变量名 LHS（局部变量）及复合赋值（`+=` 等）不采集。

---

## 3. 关联要素完备表 (Constituent Elements)

| 要素名称 | 技术描述 | 典型示例 |
| :--- | :--- | :--- |
| **源/宿 (Source/Sink)** | 关联的发起方（数据来源）与接收方（数据终点）实体 | `Order` 实体 / `Invoice` 实体 |
| **映射算子 (Operator)** | 定义数据转换与合并的逻辑 | `Direct`（直接访问）, `Concat`（拼接）, `Format`（格式化）, `Transform`（链式变换） |
| **归一化 (Normalization)** | 抹平物理差异的预处理规则 | 强制类型转换 (`Int` to `String`), 忽略大小写 |
| **谓词 (Predicate)** | 最终逻辑判定的性质 | `EQUALS`（当前实现支持），`CONTAINS`、`IN_RANGE`（待扩展） |
| **变量上下文 (Context)** | 表达式中出现的运行时变量，以变量名作为字段标识 | `currentUserId`（className=null, fieldName=变量名） |
| **溯源 (Traceability)** | 关联的原始代码表达式及其文件位置，用于审计与追踪 | `rawExpression: "a.phone.equals(b.mobile)"`, `location: "OrderService.java:42"` |

---

## 4. 边界约束策略 (Constraints)

* **空值安全性 (Null-Safety)**：定义 `null vs null` 的行为（Strict: 不成立; Lax: 成立）。[待实现]
* **类型兼容性 (Type Consistency)**：自动对齐不同包装类型（如 `Integer` 与 `Long`）的等值比较。
* **触发时机 (Binding)**：
    * **静态绑定**：开发期确定的硬编码字段映射（当前实现范围）。
    * **动态绑定**：根据变量内容在运行时决定的"延迟关联"（超出静态分析边界，不支持）。
* **类型解析策略 (Type Resolution)**：优先使用 SymbolSolver 符号解析；失败时降级为词法启发（scope 文本）；变量出现即纳入，类名标记为 `null`。
* **自关联包含 (Self-Relation Inclusion)**：同一类的字段间关联（`A.f1 ≡ A.f2`）视为有效关联，不过滤。
* **有效性过滤 (Validity Filter)**：至少一侧包含可解析类名的字段引用，否则丢弃该关联。

---

## 5. 关联分类规则 (Classification Rules)

适用于探测型与动作型两类关联，分类优先级（高→低）：

| 优先级 | 判定条件 | MappingType |
| :---: | :--- | :--- |
| 1 | 任一侧算子为 `transform`（链式方法调用） | `PARAMETERIZED` |
| 2 | 任一侧字段数 > 1，或算子为 `concat` / `format` | `COMPOSITE` |
| 3 | 其余 | `ATOMIC` |

---

## 6. 关联模式标识 (MappingMode)

| MappingMode | 含义 | 检测来源 | Mermaid 箭头 |
| :--- | :--- | :--- | :--- |
| `READ_PREDICATE` | 探测型，`equals()` 表达 | `caller.equals(arg)` | `-->` 实线 |
| `WRITE_ASSIGNMENT` | 动作型，赋值表达 | `obj.field = expr` | `-.->` 虚线 |

---

## 7. 形式化逻辑示例 (JSON Schema)

### 原子等值（READ）
```json
{
  "association_id": "MAPPING_001",
  "type": "ATOMIC",
  "mode": "READ_PREDICATE",
  "source": { "class": "Order", "fields": ["orderId"], "operator": "direct" },
  "target": { "class": "Invoice", "fields": ["refOrderId"], "operator": "direct" },
  "traceability": {
    "raw_expression": "order.orderId.equals(invoice.refOrderId)",
    "location": "OrderService.java:58"
  }
}
```

### 投影组合关联（READ）
```json
{
  "association_id": "MAPPING_002",
  "type": "COMPOSITE",
  "mode": "READ_PREDICATE",
  "source": { "class": "User", "fields": ["areaCode", "phone"], "operator": "concat" },
  "target": { "class": "Account", "fields": ["fullMobile"], "operator": "direct" },
  "logic": { "transform": "CONCAT", "pre_process": "STRIP_HYPHEN", "on_null": "REJECT_ALL" },
  "traceability": {
    "raw_expression": "(user.areaCode + user.phone).equals(account.fullMobile)",
    "location": "UserService.java:112"
  }
}
```

### 隐藏相等（WRITE）
```json
{
  "association_id": "MAPPING_003",
  "type": "ATOMIC",
  "mode": "WRITE_ASSIGNMENT",
  "source": { "class": "Order", "fields": ["userId"], "operator": "direct" },
  "target": { "class": "Invoice", "fields": ["buyerId"], "operator": "direct" },
  "traceability": {
    "raw_expression": "invoice.buyerId = order.userId",
    "location": "InvoiceFactory.java:34"
  }
}
```

### 参数化变量关联（READ，变量上下文）
```json
{
  "association_id": "MAPPING_004",
  "type": "PARAMETERIZED",
  "mode": "READ_PREDICATE",
  "source": { "class": "Order", "fields": ["tenantId"], "operator": "direct" },
  "target": { "class": null, "fields": ["currentTenantId"], "operator": "direct" },
  "note": "target className=null indicates an unresolved variable in context",
  "traceability": {
    "raw_expression": "order.tenantId.equals(currentTenantId)",
    "location": "TenantFilter.java:77"
  }
}
```

---

## 8. 已知实现差距 (Known Implementation Gaps)

以下差距已识别，协议定义超前于当前实现，暂不修复，记录于此供后续迭代参考。

### GAP-01 · 变量上下文分类错误

**协议要求**：含裸变量（`className = null` 的 `FieldRef`）的表达式应分类为 `PARAMETERIZED`。

**当前行为**：`RelationshipClassifier` 仅依据算子（`transform` / `concat` / `format`）分类，不检查 `className` 是否为 `null`。

**影响示例**：
```java
order.tenantId.equals(currentTenantId)
// 期望：PARAMETERIZED（变量参与）
// 实际：ATOMIC（误判）
```

---

### GAP-02 · 别名展开后算子检测失效

**协议要求**：算子描述应反映实际数据转换语义（展开后）。

**当前行为**：`detectOperator` 在别名展开**之前**对原始表达式运行，无法感知别名背后的组合结构。

**影响示例**：
```java
String id = user.firstName + user.lastName;
order.userId = id;
// id 展开后为 concat 结构，期望：COMPOSITE
// 实际：detectOperator(NameExpr "id") → "direct" → ATOMIC（误判）
```

---

### GAP-03 · 归一化属性未采集

**协议要求**：要素完备表第 3 项"归一化（Normalization）"——抹平物理差异的预处理规则（如 `.toLowerCase()`、`.trim()`、类型转换）应被识别并记录为独立属性。

**当前行为**：`.toLowerCase().equals(...)` 被当作 `transform` 链归类为 `PARAMETERIZED`，但归一化类型（`IGNORE_CASE`、`TRIM` 等）既未提取也未存储。`FieldMapping` 无 `normalization` 字段，JSON Schema 中的 `pre_process` 无对应实现。
