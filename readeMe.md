# 元属性逻辑关联协议汇总 (MALM Protocol Summary)

## 1. 核心定义 (Definition)
**元属性逻辑关联**是指类与类之间，基于属性值的**等值判定（Predicate）**或**状态同步（Assignment）**而建立的逻辑纽带。其本质是描述数据在不同对象维度下如何达成"语义对等"的规则集合。

> **实现范围说明**：本协议当前实现基于**静态 AST 分析**，感知范围限于源码中可静态解析的字段引用，不覆盖运行时动态绑定。分析入口为 `LineageAnalyzer`，流水线为：
> ```
> scan → parse → visit → extract → classify → aggregate → expand → render
> ```

---

## 2. 关联模式分类 (Association Modes)

### 2.1 探测型关联 (Read-Only Predicate)

通过 `equals()` 表达式检测，不修改任何状态。

* **原子等值 (Atomic Equality)**：类 $A$ 的单字段与类 $B$ 的单字段直接 `equals`。
    * *公式*：$A.f_1 \equiv B.f_1$
    * *代码示例*：`a.orderId.equals(b.userId)` 或 `Objects.equals(a.orderId, b.userId)`
* **投影组合关联 (Composite Projection)**：类 $A$ 的多个字段经算子（如拼接、格式化）后，与类 $B$ 进行等值比较。
    * *公式*：$f(A.f_1, A.f_2, ...) \equiv g(B.f_3, B.f_4, ...)$
    * *代码示例*：`(a.areaCode + a.phone).equals(b.fullMobile)`
* **参数化动态关联 (Parametric/Variable)**：引入运行时变量（Variable）或方法链变换作为桥梁。
    * *公式*：$A.f_1 \equiv V_{context} \rightarrow B.f_{target}$
    * *代码示例*：`a.getCode().transform().equals(b.value)`
    * *变量策略*：表达式中出现的裸变量名视为参与等值关联，以变量名作为字段标识，类名标记为未知（`null`）。

**探测型采集规则**：
| 形式 | 示例 | 说明 |
| :--- | :--- | :--- |
| 实例方法调用 | `caller.equals(arg)` | 最常见形式 |
| 静态工具方法 | `Objects.equals(a, b)` | Java 7+ 空安全写法，`arg[0]` 为来源侧 |
| Getter 语义剥离 | `a.getOrderId().equals(b.refId)` | `getXxx()` 自动映射为字段 `xxx` |
| Lambda 穿透 | `list.stream().filter(x -> x.getId().equals(ext))` | 穿透 Lambda 体，参数类型降级为变量名 |

---

### 2.2 动作型关联 (Write-Side Assignment)

通过赋值语义检测，表达数据从源流向目标的状态同步行为。

* **隐藏相等 (Implicit Equality)**：属性赋值本质上是强制制造"相等"状态（ATOMIC WRITE）。
    * *代码示例*：`b.mobile = a.phone`
* **拟合同步 (State Alignment)**：通过计算将 $A$ 的状态映射给 $B$（COMPOSITE / PARAMETERIZED WRITE）。
    * *代码示例*：`b.fullName = a.firstName + " " + a.lastName`

**动作型采集规则**：
| 形式 | 示例 | 说明 |
| :--- | :--- | :--- |
| 字段直接赋值 | `obj.field = expr` | LHS 须为 `FieldAccessExpr` |
| Setter 调用 | `obj.setXxx(value)` | 等价于 `obj.xxx = value`，自动识别为 WRITE_ASSIGNMENT |
| 排除复合赋值 | `obj.field += expr` | 不采集 |
| 排除局部变量赋值 | `String id = user.id` | LHS 为裸 NameExpr，不采集（但纳入别名表） |

---

### 2.3 推导型关联 (Transitive Closure)

由工具自动从已知直接关联中**演绎推导**出的间接关联，非源码直接体现。

* **传递性推导**：若 $A.f_1 \equiv B.f_2$ 且 $B.f_2 \equiv C.f_3$，则自动推导 $A.f_1 \equiv C.f_3$。
    * *实现*：固定点迭代，`PairKey` 防环防重，完整传递闭包（无跳数限制）。
    * *输出标识*：`MappingMode = TRANSITIVE_CLOSURE`，Mermaid 以 `==>` 双实线表示。
    * *溯源*：推导路径以 `"derived: [M1.raw] → [M2.raw]"` 形式记录原始链条。

---

## 3. 关联要素完备表 (Constituent Elements)

| 要素名称 | 技术描述 | 典型示例 |
| :--- | :--- | :--- |
| **源/宿 (Source/Sink)** | 关联的发起方（数据来源）与接收方（数据终点）实体 | `Order` 实体 / `Invoice` 实体 |
| **映射算子 (Operator)** | 定义数据转换与合并的逻辑 | `Direct`、`Concat`、`Format`、`Transform`、`Getter`（自动剥离） |
| **归一化 (Normalization)** | 抹平物理差异的预处理规则 | 强制类型转换 (`Int` to `String`), 忽略大小写 [待实现] |
| **谓词 (Predicate)** | 最终逻辑判定的性质 | `EQUALS`（当前实现），`CONTAINS`、`IN_RANGE`（待扩展） |
| **变量上下文 (Context)** | 表达式中出现的运行时变量，以变量名作为字段标识 | `currentUserId`（className=null, fieldName=变量名） |
| **溯源 (Traceability)** | 关联的原始代码表达式及其文件位置，用于审计与追踪 | `rawExpression`, `location: "OrderService.java:42"` |

---

## 4. 边界约束策略 (Constraints)

* **空值安全性 (Null-Safety)**：定义 `null vs null` 的行为（Strict: 不成立; Lax: 成立）。[待实现]
* **类型兼容性 (Type Consistency)**：自动对齐不同包装类型（如 `Integer` 与 `Long`）的等值比较。[待实现]
* **触发时机 (Binding)**：
    * **静态绑定**：开发期确定的硬编码字段映射（当前实现范围）。
    * **动态绑定**：根据变量内容在运行时决定的"延迟关联"（超出静态分析边界，不支持）。
* **类型解析策略 (Type Resolution)**：优先使用 SymbolSolver 符号解析；失败时降级为词法启发（scope 文本）；变量/lambda 参数出现即纳入，类名标记为 `null`。
* **局部别名追踪 (Local Alias Tracing)**：方法体内的局部变量赋值（`Type id = obj.field`）纳入别名表，后续表达式中引用该变量时自动展开为原始字段。Lambda 参数建立子作用域，防止外层别名污染。
* **Getter/Setter 语义映射**：`getXxx()` 自动识别为字段 `xxx` 的读取；`setXxx(v)` 自动识别为字段 `xxx` 的写入。
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

推导型关联固定分类为 `PARAMETERIZED`（表示跨类推断语义）。

---

## 6. 关联模式标识 (MappingMode)

| MappingMode | 含义 | 检测来源 | Mermaid 箭头 | 文档分节 |
| :--- | :--- | :--- | :--- | :--- |
| `READ_PREDICATE` | 探测型，`equals()` 表达 | `caller.equals(arg)` / `Objects.equals(a,b)` | `-->` 实线 | 字段血缘明细 |
| `WRITE_ASSIGNMENT` | 动作型，赋值表达 | `obj.field = expr` / `obj.setXxx(v)` | `-.->` 虚线 | 字段血缘明细 |
| `TRANSITIVE_CLOSURE` | 推导型，传递性推导 | 自动演绎 | `==>` 双实线 | 推导关联（独立节） |

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

### Getter 剥离（READ）
```json
{
  "association_id": "MAPPING_002",
  "type": "ATOMIC",
  "mode": "READ_PREDICATE",
  "source": { "class": "Order", "fields": ["code"], "operator": "direct" },
  "target": { "class": "Invoice", "fields": ["refCode"], "operator": "direct" },
  "note": "getCode() stripped to field 'code' by getter semantic",
  "traceability": {
    "raw_expression": "order.getCode().equals(invoice.refCode)",
    "location": "OrderService.java:62"
  }
}
```

### 投影组合关联（READ）
```json
{
  "association_id": "MAPPING_003",
  "type": "COMPOSITE",
  "mode": "READ_PREDICATE",
  "source": { "class": "User", "fields": ["areaCode", "phone"], "operator": "concat" },
  "target": { "class": "Account", "fields": ["fullMobile"], "operator": "direct" },
  "traceability": {
    "raw_expression": "(user.areaCode + user.phone).equals(account.fullMobile)",
    "location": "UserService.java:112"
  }
}
```

### Setter 调用（WRITE）
```json
{
  "association_id": "MAPPING_004",
  "type": "ATOMIC",
  "mode": "WRITE_ASSIGNMENT",
  "source": { "class": "Order", "fields": ["userId"], "operator": "direct" },
  "target": { "class": "Invoice", "fields": ["buyerId"], "operator": "direct" },
  "note": "setXxx() recognized as WRITE_ASSIGNMENT equivalent to obj.field = value",
  "traceability": {
    "raw_expression": "invoice.setBuyerId(order.userId)",
    "location": "InvoiceFactory.java:34"
  }
}
```

### 传递性推导（TRANSITIVE）
```json
{
  "association_id": "MAPPING_005",
  "type": "PARAMETERIZED",
  "mode": "TRANSITIVE_CLOSURE",
  "source": { "class": "User", "fields": ["id"], "operator": "direct" },
  "target": { "class": "Invoice", "fields": ["buyerId"], "operator": "direct" },
  "derived_from": ["MAPPING_003 (user.id → order.userId)", "MAPPING_004 (order.userId → invoice.buyerId)"],
  "traceability": {
    "raw_expression": "derived: [order.userId = 'P' + id] → [invoice.buyerId = order.userId]",
    "location": "transitive"
  }
}
```

### 参数化变量关联（READ，变量上下文）
```json
{
  "association_id": "MAPPING_006",
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

**当前行为**：`RelationshipClassifier` 仅依据算子分类，不检查 `className` 是否为 `null`。

**影响示例**：
```java
order.tenantId.equals(currentTenantId)
// 期望：PARAMETERIZED（变量参与）  实际：ATOMIC（误判）
```

---

### GAP-02 · 别名展开后算子检测失效

**协议要求**：算子描述应反映实际数据转换语义（展开后）。

**当前行为**：`detectOperator` 在别名展开之前运行，无法感知别名背后的组合结构。

**影响示例**：
```java
String id = user.firstName + user.lastName;
order.userId = id;
// 期望：COMPOSITE  实际：ATOMIC（误判）
```

---

### GAP-03 · 归一化属性未采集

**协议要求**：归一化（Normalization）应被识别并记录为独立属性（如 `IGNORE_CASE`、`TRIM`）。

**当前行为**：`.toLowerCase().equals(...)` 归类为 `PARAMETERIZED`，但归一化类型未提取，`FieldMapping` 无 `normalization` 字段。
