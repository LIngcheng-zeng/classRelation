# classRelationTestCode — 字段关联分析报告

## 摘要

| 项目 | 数值 |
|---|---|
| 涉及类关系对（直接） | 9 |
| 探测型关联（READ） | 2 |
| 动作型关联（WRITE） | 11 |
| 推导关联（传递闭包） | 0 |

## 关联图谱

```mermaid
flowchart LR
    Address -->|"PD: Address.zip ≡ User.areaCode"| User
    User -->|"CP: format(User.id, User.phone) ≡ format(Order.userId, Order.phone)"| Order
    User -.->|"CP: User.id ≡ Order.userId"| Order
    User -.->|"AE: User.name ≡ Employee.lastName"| Employee
    User -.->|"PD: User.id ≡ Invoice.buyerId"| Invoice
    OrderDTO -.->|"PD: OrderDTO.holds ≡ Order.held"| Order
    UserOrderDTO -.->|"PD: UserOrderDTO.holds ≡ OrderDTO.held"| OrderDTO
    UserOrderDTO -.->|"PD: UserOrderDTO.holds ≡ User.held"| User
    User -.->|"PD: User.phone ≡ Account.fullMobile"| Account
    User -.->|"PD: User.id ≡ Account.userId"| Account
    Order -.->|"PD: Order.orderId ≡ Invoice.refOrderId"| Invoice
```

> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。

### 关系类型说明

| 缩写 | 全称 | 含义 | 示例 |
|---|---|---|---|
| **AE** | Atomic Equality | 原子等值：单字段对单字段的直接映射 | `A.id ≡ B.userId` |
| **CP** | Composite Projection | 投影组合：多字段组合或拼接后的映射 | `A.f1 + A.f2 ≡ B.full` |
| **PD** | Parameterized / Derived | 参数化/派生：经过转换、归一化或依赖上下文的映射 | `A.code.toLowerCase() ≡ B.value` |

## 字段血缘明细

### User

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `User.areaCode` | `Address.zip` | PARAMETERIZED | READ | `CustomService.java:31` | `toLowerCase()` |
| | *address.getZip().toLowerCase().equals(user.getAreaCode())* | | | |
| `User.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getUser()* | | | |
| `User.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getUser()* | | | |

### Order

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `Order.userId`, `Order.phone` | `User.id`, `User.phone` | COMPOSITE | READ | `CustomService.java:63` |
| | *userAndPhone.equals(userAndPhone2)* | | | |
| `Order.userId` | `User.id` | COMPOSITE | WRITE | `CustomService.java:14` |
| | *order.userId = "P" + id* | | | |
| `Order.held` | `OrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getOrderDTO().getOrder()* | | | |
| `Order.held` | `OrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *orderDTO.getOrder()* | | | |

### Employee

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `Employee.lastName` | `User.name` | ATOMIC | WRITE | `CustomService.java:52` |
| | *employee.setLastName(user.getName())* | | | |

### Invoice

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `Invoice.buyerId` | `User.id` | PARAMETERIZED | WRITE | `fillInvoice(projected)` |
| | *invoice.setBuyerId(userId)* | | | |
| `Invoice.refOrderId` | `Order.orderId` | PARAMETERIZED | WRITE | `fillInvoice(projected)` |
| | *invoice.setRefOrderId(orderId)* | | | |

### OrderDTO

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `OrderDTO.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getOrderDTO()* | | | |

### Account

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 | 归一化操作 |
|---|---|---|---|---|---|
| `Account.fullMobile` | `User.phone` | PARAMETERIZED | WRITE | `main(constructor-call)` |
| | *new Account(userOrderDTO.getUser().getPhone(), userOrderDTO.getUser().getId())* | | | |
| `Account.userId` | `User.id` | PARAMETERIZED | WRITE | `main(constructor-call)` |
| | *new Account(userOrderDTO.getUser().getPhone(), userOrderDTO.getUser().getId())* | | | |

