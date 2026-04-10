package org.example.model;

import lombok.Data;

@Data
public class UserOrderDTO {
    private OrderDTO orderDTO;
    private User user;
}
