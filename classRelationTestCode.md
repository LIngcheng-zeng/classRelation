# classRelationTestCode — 字段关联分析报告

## 摘要

| 项目 | 数值 |
|---|---|
| 涉及类关系对（直接） | 8 |
| 探测型关联（READ） | 1 |
| 动作型关联（WRITE） | 11 |
| 推导关联（传递闭包） | 0 |

## 关联图谱

```mermaid
flowchart LR
    User -->|"CP: direct(User.id, User.phone) ≡ direct(Order.userId, Order.phone)"| Order
    User -.->|"CP: User.id ≡ Order.userId"| Order
    User -.->|"AE: User.id ≡ Invoice.buyerId"| Invoice
    User -.->|"PD: User.id ≡ Invoice.buyerId"| Invoice
    User -.->|"AE: User.name ≡ Employee.lastName"| Employee
    OrderDTO -.->|"PD: OrderDTO.holds ≡ Order.held"| Order
    UserOrderDTO -.->|"PD: UserOrderDTO.holds ≡ OrderDTO.held"| OrderDTO
    UserOrderDTO -.->|"PD: UserOrderDTO.holds ≡ User.held"| User
    User -.->|"PD: User.phone ≡ Account.fullMobile"| Account
    User -.->|"PD: User.id ≡ Account.userId"| Account
    Order -.->|"PD: Order.orderId ≡ Invoice.refOrderId"| Invoice
```

> 实线箭头 `-->` 为探测型（READ），虚线箭头 `-.->` 为动作型（WRITE）。

## 字段血缘明细

### Order

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Order.userId`, `Order.phone` | `User.id`, `User.phone` | COMPOSITE | READ | `CustomService.java:54` |
| | *userAndPhone.equals(userAndPhone2)* | | | |
| `Order.userId` | `User.id` | COMPOSITE | WRITE | `CustomService.java:14` |
| | *order.userId = "P" + id* | | | |
| `Order.held` | `OrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getOrderDTO().getOrder()* | | | |

### Invoice

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Invoice.buyerId` | `User.id` | ATOMIC | WRITE | `CustomService.java:34` |
| | *invoice.setBuyerId(user.getId())* | | | |
| `Invoice.buyerId` | `User.id` | PARAMETERIZED | WRITE | `generateInvoice(projected)` |
| | *invoice.setBuyerId(user.getId())* | | | |
| `Invoice.refOrderId` | `Order.orderId` | PARAMETERIZED | WRITE | `generateInvoice(projected)` |
| | *invoice.setRefOrderId(orderId)* | | | |

### Employee

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Employee.lastName` | `User.name` | ATOMIC | WRITE | `CustomService.java:43` |
| | *employee.setLastName(user.getName())* | | | |

### OrderDTO

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `OrderDTO.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getOrderDTO()* | | | |

### User

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `User.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getUser()* | | | |
| `User.held` | `UserOrderDTO.holds` | PARAMETERIZED | WRITE | `main(composition)` |
| | *userOrderDTO.getUser()* | | | |

### Account

| 目标表字段 | 源表字段集合 | 映射类型 | 模式 | 代码位置 |
|---|---|---|---|---|
| `Account.fullMobile` | `User.phone` | PARAMETERIZED | WRITE | `main(constructor-call)` |
| | *new Account(userOrderDTO.getUser().getPhone(), userOrderDTO.getUser().getId())* | | | |
| `Account.userId` | `User.id` | PARAMETERIZED | WRITE | `main(constructor-call)` |
| | *new Account(userOrderDTO.getUser().getPhone(), userOrderDTO.getUser().getId())* | | | |

