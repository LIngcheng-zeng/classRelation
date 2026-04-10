package org.example.service;

import org.example.model.User;
import org.example.model.Invoice;

/**
 * 测试场景：跨过程分析 (Inter-Procedural Analysis)
 * 
 * 验证方法调用链中的字段传递
 * 示例：generateInvoice() -> fillInvoice() 中的字段映射
 */
public class InterProceduralTest {
    
    public Invoice generateInvoice(User user, String orderId) {
        Invoice invoice = new Invoice();
        String userId = user.getId();
        
        // 跨过程调用：userId 通过参数传递到 fillInvoice
        fillInvoice(orderId, invoice, userId);
        return invoice;
    }
    
    private void fillInvoice(String orderId, Invoice invoice, String userId) {
        // 这里建立映射：userId -> invoice.buyerId, orderId -> invoice.refOrderId
        invoice.setBuyerId(userId);
        invoice.setRefOrderId(orderId);
    }
}
