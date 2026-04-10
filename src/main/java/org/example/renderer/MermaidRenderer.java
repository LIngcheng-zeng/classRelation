package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.MappingType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders lineage relations as Mermaid flowchart diagrams.
 *
 * Each connected component is emitted as a separate {@code ```mermaid} block
 * so disconnected class clusters can be read independently.
 *
 * Self-relations (sourceClass == targetClass) are rendered with a "self-call"
 * prefix in the edge label and produce a self-loop arrow in the diagram.
 */
public class MermaidRenderer {

    public String render(List<ClassRelation> relations) {
        if (relations.isEmpty()) return "No class relations found.";

        List<Set<String>> components = findConnectedComponents(relations);

        StringBuilder sb = new StringBuilder();
        for (Set<String> component : components) {
            List<ClassRelation> subset = relations.stream()
                    .filter(r -> component.contains(r.sourceClass()) || component.contains(r.targetClass()))
                    .toList();
            sb.append(renderComponent(subset));
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    // -------------------------------------------------------------------------
    // Connected component detection — Union-Find
    // -------------------------------------------------------------------------

    private List<Set<String>> findConnectedComponents(List<ClassRelation> relations) {
        Map<String, String> parent = new LinkedHashMap<>();

        for (ClassRelation rel : relations) {
            parent.putIfAbsent(rel.sourceClass(), rel.sourceClass());
            parent.putIfAbsent(rel.targetClass(), rel.targetClass());
            // Self-loops do not merge two distinct nodes
            if (!rel.sourceClass().equals(rel.targetClass())) {
                union(parent, rel.sourceClass(), rel.targetClass());
            }
        }

        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (String node : parent.keySet()) {
            groups.computeIfAbsent(find(parent, node), k -> new LinkedHashSet<>()).add(node);
        }
        return new ArrayList<>(groups.values());
    }

    private String find(Map<String, String> parent, String x) {
        if (!parent.get(x).equals(x)) {
            parent.put(x, find(parent, parent.get(x)));   // path compression
        }
        return parent.get(x);
    }

    private void union(Map<String, String> parent, String x, String y) {
        String rx = find(parent, x);
        String ry = find(parent, y);
        if (!rx.equals(ry)) parent.put(rx, ry);
    }

    // -------------------------------------------------------------------------
    // Component rendering
    // -------------------------------------------------------------------------

    private String renderComponent(List<ClassRelation> relations) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append("flowchart LR\n");

        Set<String> drawnEdges = new LinkedHashSet<>();

        for (ClassRelation rel : relations) {
            String  src    = sanitize(rel.sourceClass());
            String  tgt    = sanitize(rel.targetClass());
            boolean isSelf = src.equals(tgt);

            for (FieldMapping m : rel.mappings()) {
                String label = buildLabel(m, isSelf);
                String arrow = switch (m.mode()) {
                    case WRITE_ASSIGNMENT   -> "-.->";
                    case TRANSITIVE_CLOSURE -> "==>";
                    default                -> "-->";
                };
                drawnEdges.add("    " + src + " " + arrow + "|\"" + label + "\"| " + tgt);
            }
        }

        drawnEdges.forEach(e -> sb.append(e).append("\n"));
        sb.append("```");
        return sb.toString();
    }

    private String buildLabel(FieldMapping m, boolean isSelf) {
        String type  = abbrev(m.type());
        String left  = m.leftSide().toString();
        String right = m.rightSide().toString();
        if (left.length()  > 40) left  = left.substring(0, 37)  + "...";
        if (right.length() > 40) right = right.substring(0, 37) + "...";
        String prefix = isSelf ? "self-call " : "";
        return prefix + type + ": " + left + " ≡ " + right;
    }

    private String abbrev(MappingType type) {
        return switch (type) {
            case ATOMIC        -> "AE";
            case COMPOSITE     -> "CP";
            case PARAMETERIZED -> "PD";
        };
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
