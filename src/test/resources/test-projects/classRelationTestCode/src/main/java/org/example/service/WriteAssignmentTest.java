package org.example.service;

import org.example.model.User;
import org.example.model.Invoice;

/**
 * 测试场景：动作型关联 - 写赋值 (Write Assignment)
 * 
 * 验证通过 setter 方法进行的状态同步
 * 示例：invoice.setBuyerId(userId); invoice.setRefOrderId(orderId);
 */
public class WriteAssignmentTest {
    
    public Invoice createInvoiceFromUser(User user, String orderId) {
        Invoice invoice = new Invoice();
        String userId = user.getId();
        
        // 写赋值：userId -> buyerId, orderId -> refOrderId
        fillInvoice(orderId, invoice, userId);
        return invoice;
    }
    
    private void fillInvoice(String orderId, Invoice invoice, String userId) {
        invoice.setBuyerId(userId);
        invoice.setRefOrderId(orderId);
    }
}
