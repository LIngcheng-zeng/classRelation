package org.example.model;


import lombok.Data;

@Data
public class Account {

    public String fullMobile;
    public String userId;

    public Account(String fullMobile, String userId) {
        this.fullMobile = fullMobile;
        this.userId = userId;
    }
}
