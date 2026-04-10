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
    
    // Inheritance information from AnalysisContext
    private Map<String, ClassRelation.InheritanceInfo> inheritanceMap = Collections.emptyMap();

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
    
    /**
     * Sets the inheritance map detected by SpoonAnalyzer.
     */
    public void setInheritanceMap(Map<String, ClassRelation.InheritanceInfo> inheritanceMap) {
        this.inheritanceMap = inheritanceMap != null ? inheritanceMap : Collections.emptyMap();
    }

    public List<ClassRelation> buildRelations() {
        List<ClassRelation> relations = new ArrayList<>();
        
        // Add regular field mapping relations
        for (Map.Entry<String, List<FieldMapping>> entry : graph.entrySet()) {
            String[] parts = entry.getKey().split("->", 2);
            String sourceClass = parts[0];
            String targetClass = parts[1];
            
            // Check if there's an inheritance relationship
            ClassRelation.InheritanceInfo inheritance = findInheritance(sourceClass, targetClass);
            
            relations.add(new ClassRelation(sourceClass, targetClass, entry.getValue(), inheritance));
        }
        
        // Add pure inheritance relations (even without field mappings)
        Set<String> existingKeys = graph.keySet();
        for (Map.Entry<String, ClassRelation.InheritanceInfo> entry : inheritanceMap.entrySet()) {
            String childClass = entry.getKey();
            String parentClass = entry.getValue().parentClass();
            
            // Check if this inheritance relation already exists in graph
            String key1 = childClass + "->" + parentClass;
            String key2 = parentClass + "->" + childClass;
            
            if (!existingKeys.contains(key1) && !existingKeys.contains(key2)) {
                // Create a pure inheritance relation with no field mappings
                relations.add(new ClassRelation(childClass, parentClass, List.of(), entry.getValue()));
            }
        }
        
        return relations;
    }
    
    /**
     * Finds inheritance relationship between two classes.
     * Returns InheritanceInfo if one class extends the other, null otherwise.
     */
    private ClassRelation.InheritanceInfo findInheritance(String class1, String class2) {
        // Check if class1 extends class2
        ClassRelation.InheritanceInfo info = inheritanceMap.get(class1);
        if (info != null && info.parentClass().equals(class2)) {
            return info;
        }
        
        // Check if class2 extends class1
        info = inheritanceMap.get(class2);
        if (info != null && info.parentClass().equals(class1)) {
            return info;
        }
        
        return null;
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
