package org.example.service;

import org.example.model.UserOrderDTO;
import org.example.model.OrderDTO;
import org.example.model.Order;

/**
 * 测试场景：Getter 链式调用 (Getter Chain)
 * 
 * 验证深层嵌套的 getter 调用链
 * 示例：userOrderDTO.getOrderDTO().getOrder().getOrderId()
 */
public class GetterChainTest {
    
    public String extractOrderId(UserOrderDTO userOrderDTO) {
        // Getter 链：深度嵌套访问
        String orderId = userOrderDTO.getOrderDTO().getOrder().getOrderId();
        return orderId;
    }
    
    public String extractUserPhone(UserOrderDTO userOrderDTO) {
        // 另一个 getter 链
        String phone = userOrderDTO.getUser().getPhone();
        return phone;
    }
}
