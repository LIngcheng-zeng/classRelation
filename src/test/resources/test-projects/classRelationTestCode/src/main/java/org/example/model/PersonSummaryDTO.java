package org.example.model;

import lombok.Data;

/**
 * Multi-source demo: same fields can be populated from either User or Employee.
 */
@Data
public class PersonSummaryDTO {

    private String displayName;   // User.name  OR  Employee.fullName
    private String mobile;        // User.phone OR  Employee.departmentCode
    private String orgCode;       // User.tenantId  OR  Employee.employeeNo
}
