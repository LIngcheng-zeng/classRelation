package org.example.service;

import org.example.model.VipUser;
import org.example.model.OrderDTO;

/**
 * 测试场景：继承关系 (Inheritance)
 * 
 * 验证子类使用继承自父类的字段
 * 示例：VipUser extends User，使用 id/name 等继承字段
 */
public class InheritanceTest {
    
    public void testVipUserInheritedFields() {
        VipUser vipUser = new VipUser();
        OrderDTO orderDTO = new OrderDTO();
        
        // VipUser 继承自 User，可以使用 id 字段
        vipUser.setId(orderDTO.getOrder().getUserId());
        
        // 也可以使用 name 等其他继承字段
        String vipName = vipUser.getName();
    }
}
