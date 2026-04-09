package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;
import org.example.model.MappingType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders lineage relations as a Mermaid flowchart diagram.
 *
 * Example output:
 *   flowchart LR
 *     Order -->|ATOMIC: orderId = userId| User
 */
public class MermaidRenderer {

    public String render(List<ClassRelation> relations) {
        if (relations.isEmpty()) return "No class relations found.";

        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append("flowchart LR\n");

        Set<String> drawnEdges = new LinkedHashSet<>();

        for (ClassRelation rel : relations) {
            String src = sanitize(rel.sourceClass());
            String tgt = sanitize(rel.targetClass());

            for (FieldMapping m : rel.mappings()) {
                String label = buildLabel(m);
                // READ_PREDICATE: solid -->  WRITE_ASSIGNMENT: dashed -.->  TRANSITIVE: ==>=
                String arrow = switch (m.mode()) {
                    case WRITE_ASSIGNMENT   -> "-.->";
                    case TRANSITIVE_CLOSURE -> "==>";
                    default                -> "-->";
                };
                String edge = "    " + src + " " + arrow + "|\"" + label + "\"| " + tgt;
                drawnEdges.add(edge);
            }
        }

        drawnEdges.forEach(e -> sb.append(e).append("\n"));
        sb.append("```");
        return sb.toString();
    }

    private String buildLabel(FieldMapping m) {
        String type = abbrev(m.type());
        String left  = m.leftSide().toString();
        String right = m.rightSide().toString();
        // Truncate long expressions for readability
        if (left.length()  > 40) left  = left.substring(0, 37) + "...";
        if (right.length() > 40) right = right.substring(0, 37) + "...";
        return type + ": " + left + " ≡ " + right;
    }

    private String abbrev(MappingType type) {
        return switch (type) {
            case ATOMIC        -> "AE";   // Atomic Equality / Atomic Write
            case COMPOSITE     -> "CP";   // Composite Projection / Composite Write
            case PARAMETERIZED -> "PD";   // Parameterized Dynamic / Parameterized Write
        };
    }

    /** Mermaid node IDs cannot contain dots or special chars */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
