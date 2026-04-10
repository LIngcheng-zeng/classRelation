# classRelationTestCode — 字段关联分析报告

## 摘要

| 项目 | 数值 |
|---|---|
| 涉及类关系对（直接） | 4 |
| 探测型关联（READ） | 1 |
| 动作型关联（WRITE） | 5 |
| 推导关联（传递闭包） | 0 |

## 关联图谱

```mermaid
flowchart LR
    User -->|"CP: direct(User.id, User.phone) ≡ direct(Order.userId, Order.phone)"| Order
    User -.->|"CP: User.id ≡ Order.userId"| Order
    User -.->|"AE: User.id ≡ Invoice.buyerId"| Invoice
    User -.->|"PD: User.id ≡ Invoice.buyerId"| Invoice
    User -.->|"AE: User.name ≡ Employee.lastName"| Employee
    orderDTO_getOrder__ -.->|"PD: orderDTO.getOrder().orderId ≡ Invoice.refOrderId"| Invoice
```

> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。

## 字段血缘明细

### Order

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Order.userId`, `Order.phone` | `User.id`, `User.phone` | COMPOSITE | READ | `CustomService.java:52` |
| | *userAndPhone.equals(userAndPhone2)* | | | |
| `Order.userId` | `User.id` | COMPOSITE | WRITE | `CustomService.java:14` |
| | *order.userId = "P" + id* | | | |

### Invoice

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Invoice.buyerId` | `User.id` | ATOMIC | WRITE | `CustomService.java:32` |
| | *invoice.setBuyerId(user.getId())* | | | |
| `Invoice.buyerId` | `User.id` | PARAMETERIZED | WRITE | `generateInvoice(projected)` |
| | *invoice.setBuyerId(user.getId())* | | | |
| `Invoice.refOrderId` | `orderDTO.getOrder().orderId` | PARAMETERIZED | WRITE | `generateInvoice(projected)` |
| | *invoice.setRefOrderId(orderId)* | | | |

### Employee

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Employee.lastName` | `User.name` | ATOMIC | WRITE | `CustomService.java:41` |
| | *employee.setLastName(user.getName())* | | | |

