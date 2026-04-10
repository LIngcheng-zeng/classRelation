package org.example.model;

import lombok.Data;

import java.util.List;

@Data
public class OrderDTO {
    private Order order;
    private List<Item> items;
}
