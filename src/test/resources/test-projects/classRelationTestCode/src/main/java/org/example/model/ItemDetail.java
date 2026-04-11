package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ItemDetail {
    private Item item;
    private String description;
}
