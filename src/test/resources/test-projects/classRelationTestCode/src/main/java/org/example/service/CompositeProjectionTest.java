package org.example.service;

import org.example.model.User;
import org.example.model.Order;

/**
 * 测试场景：投影组合关系 (Composite Projection)
 * 
 * 验证多字段经过算子（拼接、格式化）后的等值比较
 * 示例：String.format("_", user.id, user.phone).equals(String.format("_", order.userId, order.phone))
 */
public class CompositeProjectionTest {
    
    public void testUserOrderCompositeEquality() {
        User user = new User();
        Order order = new Order();
        
        // 投影组合：多个字段格式化后比较
        String userAndPhone = String.format("%s_%s", user.getId(), user.getPhone());
        String orderAndPhone = String.format("%s_%s", order.getUserId(), order.getPhone());
        boolean matches = userAndPhone.equals(orderAndPhone);
    }
}
