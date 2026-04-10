package org.example.service;

import org.example.model.Address;
import org.example.model.OrderDTO;
import org.example.model.User;

/**
 * 测试场景：Builder 模式 (Builder Pattern)
 * 
 * 验证构建器模式的字段提取
 * 示例：Address.builder().city(orderDTO.getOrder().getCity()).build()
 */
public class BuilderPatternTest {
    
    public Address buildAddressFromOrder(OrderDTO orderDTO) {
        // Builder 模式：从订单中提取城市构建地址
        Address address = Address.builder()
                .city(orderDTO.getOrder().getCity())
                .build();
        return address;
    }
    
    public boolean matchUserAreaCode(Address address, User user) {
        // 归一化操作 + 等值比较
        return address.getZip().toLowerCase().equals(user.getAreaCode());
    }
}
