package org.example.graph;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;

import java.util.*;

/**
 * Aggregates FieldMappings into ClassRelations.
 * Key: "SourceClass->TargetClass"
 */
public class LineageGraph {

    // key: "ClassA->ClassB", value: list of mappings
    private final Map<String, List<FieldMapping>> graph = new LinkedHashMap<>();

    public void addMapping(FieldMapping mapping) {
        List<String> leftClasses  = distinctClasses(mapping.leftSide().fields());
        List<String> rightClasses = distinctClasses(mapping.rightSide().fields());

        // Cross-product: each left class → each right class (self-relations included)
        for (String src : leftClasses) {
            for (String tgt : rightClasses) {
                String key = src + "->" + tgt;
                graph.computeIfAbsent(key, k -> new ArrayList<>()).add(mapping);
            }
        }
    }

    public List<ClassRelation> buildRelations() {
        List<ClassRelation> relations = new ArrayList<>();
        for (Map.Entry<String, List<FieldMapping>> entry : graph.entrySet()) {
            String[] parts = entry.getKey().split("->", 2);
            relations.add(new ClassRelation(parts[0], parts[1], entry.getValue()));
        }
        return relations;
    }

    private List<String> distinctClasses(List<FieldRef> fields) {
        if (fields == null) return List.of();
        return fields.stream()
                .map(FieldRef::className)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
