package org.example.service;

import org.example.model.Item;
import org.example.model.ItemDetail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenericTest {
    public void testGeneric() {
        Map<String, List<Item>> itemIdMapItem = new HashMap<>();

        itemIdMapItem.entrySet().stream()
                .map(entry -> entry.getValue().stream().map(item -> ItemDetail.builder().item(item).description(entry.getKey()).build()));
    }
}
