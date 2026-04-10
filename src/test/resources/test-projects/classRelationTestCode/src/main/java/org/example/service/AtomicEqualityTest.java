package org.example.service;

import org.example.model.Order;
import org.example.model.Invoice;

/**
 * 测试场景：原子等值关系 (Atomic Equality)
 * 
 * 验证单字段对单字段的 equals 比较
 * 示例：order.orderId.equals(invoice.refOrderId)
 */
public class AtomicEqualityTest {
    
    public void testOrderInvoiceEquality() {
        Order order = new Order();
        Invoice invoice = new Invoice();
        
        // 原子等值：orderId <-> refOrderId
        boolean matches = order.getOrderId().equals(invoice.getRefOrderId());
    }
}
