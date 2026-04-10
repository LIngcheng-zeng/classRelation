package org.example.service;

import org.example.model.Address;
import org.example.model.User;

/**
 * 测试场景：归一化操作 (Normalization Operations)
 * 
 * 验证字符串转换、格式化等归一化操作
 * 示例：address.getZip().toLowerCase().equals(user.getAreaCode())
 */
public class NormalizationTest {
    
    public boolean compareAreaCode(Address address, User user) {
        // 归一化操作：toLowerCase
        return address.getZip().toLowerCase().equals(user.getAreaCode());
    }
    
    public String normalizeUserCode(User user) {
        // 多种归一化操作组合
        String code = user.getId();
        if (code != null) {
            code = code.trim().toUpperCase();
        }
        return code;
    }
}
