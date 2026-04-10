package org.example.service;

import org.example.model.User;
import org.example.model.Account;
import org.example.model.UserOrderDTO;

/**
 * 测试场景：构造函数调用 (Constructor Call)
 * 
 * 验证通过构造函数参数传递建立的字段映射
 * 示例：new Account(user.getPhone(), user.getId())
 */
public class ConstructorCallTest {
    
    public Account createAccountFromUser(UserOrderDTO userOrderDTO) {
        // 构造函数调用：user.phone -> Account.fullMobile, user.id -> Account.userId
        Account account = new Account(
            userOrderDTO.getUser().getPhone(),
            userOrderDTO.getUser().getId()
        );
        return account;
    }
    
    public Account createSimpleAccount(User user) {
        // 简化的构造函数调用
        Account account = new Account(user.getPhone(), user.getId());
        return account;
    }
}
