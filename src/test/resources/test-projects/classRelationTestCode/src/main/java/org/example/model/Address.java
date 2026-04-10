package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Address {
    private String street;
    private String city;
    private String state;
    private String zip;
    private String country;
}
