# classRelationTestCode — 字段关联分析报告

## 摘要

| 项目 | 数值 |
|---|---|
| 涉及类关系对（直接） | 4 |
| 探测型关联（READ） | 3 |
| 动作型关联（WRITE） | 4 |
| 推导关联（传递闭包） | 2 |

## 关联图谱

```mermaid
flowchart LR
    order -->|"AE: order.orderId ≡ invoice.refOrderId"| invoice
    order -.->|"PD: order.code ≡ invoice.refCode"| invoice
    order -.->|"AE: order.userId ≡ invoice.buyerId"| invoice
    employee -.->|"CP: concat(employee.firstName, employee.l... ≡ employee.fullName"| employee
    user -->|"CP: direct(user.areaCode, user.phone) ≡ account.fullMobile"| account
    user -->|"CP: direct(user.id, user.phone) ≡ direct(order.userId, order.phone)"| order
    user -.->|"CP: user.id ≡ order.userId"| order
    __derived__ ==>|"PD: direct(user.id, user.phone) ≡ invoice.buyerId"| invoice
    __derived__ ==>|"PD: user.id ≡ invoice.buyerId"| invoice
```

> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。

## 字段血缘明细

### invoice

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `invoice.refOrderId` | `order.orderId` | ATOMIC | READ | `AtomicReadService.java:21` |
| | *order.orderId.equals(invoice.refOrderId)* | | | |
| `invoice.refCode` | `order.code` | PARAMETERIZED | WRITE | `ParameterizedWriteService.java:25` |
| | *invoice.refCode = order.getCode().transform()* | | | |
| `invoice.buyerId` | `order.userId` | ATOMIC | WRITE | `AtomicWriteService.java:23` |
| | *invoice.buyerId = order.userId* | | | |

### employee

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `employee.fullName` | `employee.firstName`, `employee.lastName` | COMPOSITE | WRITE | `CompositeWriteService.java:23` |
| | *employee.fullName = employee.firstName + " " + employee.lastName* | | | |

### account

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `account.fullMobile` | `user.areaCode`, `user.phone` | COMPOSITE | READ | `CompositeReadService.java:21` |
| | *(user.areaCode + user.phone).equals(account.fullMobile)* | | | |

### order

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `order.userId`, `order.phone` | `user.id`, `user.phone` | COMPOSITE | READ | `CustomService.java:20` |
| | *userAndPhone.equals(userAndPhone2)* | | | |
| `order.userId` | `user.id` | COMPOSITE | WRITE | `CustomService.java:11` |
| | *order.userId = "P" + id* | | | |

## 推导关联（传递性闭包）

> 以下关联由工具自动推导，非源码直接体现。

### invoice

| 目标表字段 | 源表字段集合 | 推导路径 |
|---|---|---|
| `invoice.buyerId` | `user.id`, `user.phone` | *derived: [userAndPhone.equals(userAndPhone2)] → [invoice.buyerId = order.userId]* |
| `invoice.buyerId` | `user.id` | *derived: [order.userId = "P" + id] → [invoice.buyerId = order.userId]* |

