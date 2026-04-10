package org.example.model;

import lombok.Data;

@Data
public class Order {

    public String orderId;
    public String userId;
    public String tenantId;
    public String areaCode;
    public String phone;
    private String city;

    public CodeRef getCode() {
        return new CodeRef(orderId + "-CODE");
    }
}
