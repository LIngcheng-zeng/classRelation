package org.example.service;

import org.example.model.Employee;
import org.example.model.User;

import java.util.List;

/**
 * 测试场景：递归方法调用 (Recursive Method Call)
 * 
 * 验证递归方法中的字段映射
 * 示例：iterateCall() 递归调用中的 employee/user 字段
 */
public class RecursiveCallTest {
    
    public void processEmployees(List<Employee> employeeList) {
        iterateCall(employeeList);
    }
    
    private void iterateCall(List<Employee> employeeList) {
        Employee employee = new Employee();
        
        // 字段映射：list.size() -> employeeNo
        employee.setEmployeeNo(String.valueOf(employeeList.size()));
        
        User user = new User();
        // 字段映射：user.name -> employee.lastName
        employee.setLastName(user.getName());
        
        if (employeeList.size() > 10) {
            return;
        }
        
        employeeList.add(employee);
        // 递归调用
        iterateCall(employeeList);
    }
}
