package org.example.service;

import org.example.model.Employee;
import org.example.model.PersonSummaryDTO;
import org.example.model.User;

/**
 * Multi-source mapping demo.
 *
 * PersonSummaryDTO.displayName is populated from two genuinely different sources:
 *   path-1 (fromUser):     User.name        → displayName
 *   path-2 (fromEmployee): Employee.fullName → displayName
 *
 * Same pattern for mobile and orgCode, giving a clean 3-field × 2-source table.
 */
public class MultiSourceMappingTest {

    public PersonSummaryDTO fromUser(User user) {
        PersonSummaryDTO dto = new PersonSummaryDTO();
        dto.setDisplayName(user.getName());
        dto.setMobile(user.getPhone());
        dto.setOrgCode(user.getTenantId());
        return dto;
    }

    public PersonSummaryDTO fromEmployee(Employee emp) {
        PersonSummaryDTO dto = new PersonSummaryDTO();
        dto.setDisplayName(emp.getFullName());
        dto.setMobile(emp.getDepartmentCode());
        dto.setOrgCode(emp.getEmployeeNo());
        return dto;
    }
}
