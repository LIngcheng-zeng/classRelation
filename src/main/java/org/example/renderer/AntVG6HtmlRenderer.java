package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.util.ClassNameValidator;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Renders class lineage relations as a standalone interactive HTML page
 * powered by AntV G6 v5 (force layout).
 *
 * - One node per unique simple class name.
 * - Multiple ClassRelations sharing the same (source, target) → one aggregated edge.
 * - Inheritance → dedicated INHERIT-type edge (dashed green).
 */
public class AntVG6HtmlRenderer {

    public String render(String projectName, List<ClassRelation> relations) {
        return render(projectName, relations, 0);
    }

    /**
     * Renders class lineage relations as a standalone interactive HTML page.
     *
     * @param projectName project name for display
     * @param relations class relations to render
     * @param maxComponentsToRender maximum number of connected components to render (0 = all)
     *                              Components are selected by edge count (most edges first)
     */
    public String render(String projectName, List<ClassRelation> relations, int maxComponentsToRender) {
        Set<String> nodeIds = collectNodeIds(relations);
        Map<String, Integer> componentMap = computeComponents(nodeIds, relations);
        
        // Filter components if maxComponentsToRender is specified
        List<Map<String, Object>> nodes = buildNodes(relations, componentMap);
        List<Map<String, Object>> edges = buildEdges(relations);
        
        if (maxComponentsToRender > 0) {
            FilterResult filtered = filterTopComponents(nodes, edges, maxComponentsToRender);
            nodes = filtered.nodes;
            edges = filtered.edges;
        }
        
        return renderHtml(projectName, nodes, edges);
    }

    // -------------------------------------------------------------------------
    // Node construction
    // -------------------------------------------------------------------------

    /**
     * Filters nodes and edges to keep only the top N connected components by size.
     * Component size is primarily determined by node count, with edge count as tiebreaker.
     */
    private FilterResult filterTopComponents(List<Map<String, Object>> nodes,
                                              List<Map<String, Object>> edges,
                                              int maxComponents) {
        if (maxComponents <= 0 || nodes.isEmpty()) {
            return new FilterResult(nodes, edges);
        }

        // Build node ID to component ID map for fast lookup
        Map<String, Integer> nodeToComponent = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) node.get("data");
            if (data != null && data.containsKey("componentId")) {
                nodeToComponent.put((String) node.get("id"), (Integer) data.get("componentId"));
            }
        }

        // Count nodes per component
        Map<Integer, Integer> nodeCountByComponent = new LinkedHashMap<>();
        for (Integer compId : nodeToComponent.values()) {
            nodeCountByComponent.merge(compId, 1, Integer::sum);
        }

        // Count edges per component
        Map<Integer, Integer> edgeCountByComponent = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String sourceId = (String) edge.get("source");
            Integer compId = nodeToComponent.get(sourceId);
            if (compId != null) {
                edgeCountByComponent.merge(compId, 1, Integer::sum);
            }
        }
        
        // Ensure all components are represented
        Set<Integer> allComponentIds = new LinkedHashSet<>(nodeToComponent.values());
        for (Integer compId : allComponentIds) {
            nodeCountByComponent.computeIfAbsent(compId, k -> 0);
            edgeCountByComponent.computeIfAbsent(compId, k -> 0);
        }
        
        // Sort components by size: primary=node count, secondary=edge count (both descending)
        List<Integer> sortedComponentIds = new ArrayList<>(allComponentIds);
        sortedComponentIds.sort((a, b) -> {
            int nodeCompare = Integer.compare(
                nodeCountByComponent.getOrDefault(b, 0),
                nodeCountByComponent.getOrDefault(a, 0)
            );
            if (nodeCompare != 0) return nodeCompare;
            return Integer.compare(
                edgeCountByComponent.getOrDefault(b, 0),
                edgeCountByComponent.getOrDefault(a, 0)
            );
        });
        
        // Take top N components
        Set<Integer> selectedComponentIds = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(maxComponents, sortedComponentIds.size()); i++) {
            selectedComponentIds.add(sortedComponentIds.get(i));
        }
        
        // Debug logging
        System.out.println("\n=== Connected Component Filtering ===");
        System.out.println("Total components: " + allComponentIds.size() + 
                         ", Selected top: " + selectedComponentIds.size() + 
                         " (maxComponents=" + maxComponents + ")");
        System.out.println("Component ranking (by node count, then edge count):");
        for (int i = 0; i < sortedComponentIds.size(); i++) {
            Integer compId = sortedComponentIds.get(i);
            int nodeCount = nodeCountByComponent.getOrDefault(compId, 0);
            int edgeCount = edgeCountByComponent.getOrDefault(compId, 0);
            String marker = selectedComponentIds.contains(compId) ? " ✓ SELECTED" : " ✗ filtered";
            System.out.printf("  #%d Component %d: %d nodes, %d edges%s%n", 
                            i + 1, compId, nodeCount, edgeCount, marker);
        }
        
        // Filter nodes and edges
        List<Map<String, Object>> filteredNodes = new ArrayList<>();
        Set<String> keptNodeIds = new LinkedHashSet<>();
        
        for (Map<String, Object> node : nodes) {
            Integer compId = nodeToComponent.get(node.get("id"));
            if (compId != null && selectedComponentIds.contains(compId)) {
                filteredNodes.add(node);
                keptNodeIds.add((String) node.get("id"));
            }
        }
        
        List<Map<String, Object>> filteredEdges = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (keptNodeIds.contains(edge.get("source")) && 
                keptNodeIds.contains(edge.get("target"))) {
                filteredEdges.add(edge);
            }
        }
        
        System.out.println("\nFiltering result:");
        System.out.println("  Nodes: " + nodes.size() + " -> " + filteredNodes.size() + 
                         " (removed " + (nodes.size() - filteredNodes.size()) + ")");
        System.out.println("  Edges: " + edges.size() + " -> " + filteredEdges.size() + 
                         " (removed " + (edges.size() - filteredEdges.size()) + ")");
        System.out.println("=================================\n");
        
        return new FilterResult(filteredNodes, filteredEdges);
    }

    /**
     * Simple record-like class to hold filter results.
     */
    private static class FilterResult {
        final List<Map<String, Object>> nodes;
        final List<Map<String, Object>> edges;
        
        FilterResult(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    private List<Map<String, Object>> buildNodes(List<ClassRelation> relations, Map<String, Integer> componentMap) {
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> parents = new LinkedHashSet<>();
        Map<String, String> fqnMap = new LinkedHashMap<>();   // simpleName → FQN

        for (ClassRelation rel : relations) {
            if (!"__derived__".equals(rel.simpleSourceClass())) {
                nodeIds.add(rel.simpleSourceClass());
                fqnMap.putIfAbsent(rel.simpleSourceClass(), rel.sourceClass());
            }
            nodeIds.add(rel.simpleTargetClass());
            fqnMap.putIfAbsent(rel.simpleTargetClass(), rel.targetClass());
            if (rel.inheritance() != null) {
                nodeIds.add(rel.inheritance().simpleChildClass());
                nodeIds.add(rel.inheritance().simpleParentClass());
                parents.add(rel.inheritance().simpleParentClass());
                fqnMap.putIfAbsent(rel.inheritance().simpleChildClass(),  rel.inheritance().childClass());
                fqnMap.putIfAbsent(rel.inheritance().simpleParentClass(), rel.inheritance().parentClass());
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String id : nodeIds) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nodeType",    parents.contains(id) ? "parent" : "normal");
            data.put("componentId", componentMap.getOrDefault(id, 0));
            data.put("fqn",         fqnMap.getOrDefault(id, id));

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id",   id);
            node.put("data", data);
            nodes.add(node);
        }
        return nodes;
    }

    // -------------------------------------------------------------------------
    // Connected-component detection  (Union-Find, undirected)
    // -------------------------------------------------------------------------

    private Set<String> collectNodeIds(List<ClassRelation> relations) {
        Set<String> ids = new LinkedHashSet<>();
        for (ClassRelation rel : relations) {
            if (!"__derived__".equals(rel.simpleSourceClass())) {
                ids.add(rel.simpleSourceClass());
            }
            ids.add(rel.simpleTargetClass());
            if (rel.inheritance() != null) {
                ids.add(rel.inheritance().simpleChildClass());
                ids.add(rel.inheritance().simpleParentClass());
            }
        }
        return ids;
    }

    /** Returns a map from nodeId → 0-based component index. */
    private Map<String, Integer> computeComponents(Set<String> nodeIds, List<ClassRelation> relations) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (String id : nodeIds) parent.put(id, id);

        for (ClassRelation rel : relations) {
            if ("__derived__".equals(rel.simpleSourceClass())) continue;
            union(parent, rel.simpleSourceClass(), rel.simpleTargetClass());
            if (rel.inheritance() != null) {
                union(parent, rel.inheritance().simpleChildClass(),
                        rel.inheritance().simpleParentClass());
            }
        }

        Map<String, Integer> rootToId = new LinkedHashMap<>();
        Map<String, Integer> result   = new LinkedHashMap<>();
        int[] next = {0};
        for (String id : nodeIds) {
            String root = find(parent, id);
            rootToId.computeIfAbsent(root, k -> next[0]++);
            result.put(id, rootToId.get(root));
        }
        return result;
    }

    private String find(Map<String, String> parent, String id) {
        while (!parent.get(id).equals(id)) {
            String gp = parent.get(parent.get(id));
            if (gp != null) parent.put(id, gp);   // path compression
            id = parent.get(id);
        }
        return id;
    }

    private void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) parent.put(rootA, rootB);
    }

    // -------------------------------------------------------------------------
    // Edge construction  (aggregate by simple source → simple target)
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> buildEdges(List<ClassRelation> relations) {
        // key: src + NUL + tgt  →  accumulated non-transitive FieldMappings
        Map<String, List<FieldMapping>> mappingsByPair = new LinkedHashMap<>();
        Set<String> inheritPairs = new LinkedHashSet<>();   // child + NUL + parent

        for (ClassRelation rel : relations) {
            String src = rel.simpleSourceClass();
            String tgt = rel.simpleTargetClass();

            List<FieldMapping> direct = rel.mappings().stream()
                    .filter(m -> m.mode() != MappingMode.TRANSITIVE_CLOSURE)
                    .toList();
            if (!direct.isEmpty()) {
                mappingsByPair
                        .computeIfAbsent(src + "\u0000" + tgt, k -> new ArrayList<>())
                        .addAll(direct);
            }

            if (rel.inheritance() != null) {
                inheritPairs.add(rel.inheritance().simpleChildClass()
                        + "\u0000" + rel.inheritance().simpleParentClass());
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();

        // Field-mapping edges
        for (Map.Entry<String, List<FieldMapping>> entry : mappingsByPair.entrySet()) {
            int    sep      = entry.getKey().indexOf('\u0000');
            String src      = entry.getKey().substring(0, sep);
            String tgt      = entry.getKey().substring(sep + 1);
            List<FieldMapping> mappings = entry.getValue();

            List<FieldMapping> sortedMappings = RelationDisplaySorter.sortMappings(mappings);
            List<FieldMapping> dedupedMappings = deduplicateMappings(sortedMappings);
            String edgeType   = resolveEdgeType(dedupedMappings);
            String shortLabel = buildShortLabel(dedupedMappings);
            String tooltip    = buildTooltipHtml(src, tgt, sortedMappings);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("edgeType",    edgeType);
            data.put("shortLabel",  shortLabel);
            data.put("tooltipHtml", tooltip);

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id",     src + "__" + tgt);
            edge.put("source", src);
            edge.put("target", tgt);
            edge.put("data",   data);
            edges.add(edge);
        }

        // Inheritance edges
        for (String pair : inheritPairs) {
            int    sep    = pair.indexOf('\u0000');
            String child  = pair.substring(0, sep);
            String parent = pair.substring(sep + 1);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("edgeType",    "INHERIT");
            data.put("shortLabel",  "extends");
            data.put("tooltipHtml",
                    "<b>" + htmlEscape(child) + "</b> extends <b>" + htmlEscape(parent) + "</b>");

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id",     "INHERIT__" + child + "__" + parent);
            edge.put("source", child);
            edge.put("target", parent);
            edge.put("data",   data);
            edges.add(edge);
        }

        return edges;
    }

    // -------------------------------------------------------------------------
    // Edge type / label helpers
    // -------------------------------------------------------------------------

    private String resolveEdgeType(List<FieldMapping> mappings) {
        boolean hasRead  = mappings.stream().anyMatch(m -> m.mode() == MappingMode.READ_PREDICATE);
        boolean hasWrite = mappings.stream().anyMatch(m -> m.mode() == MappingMode.WRITE_ASSIGNMENT);
        if (hasRead && hasWrite) return "MIXED";
        if (hasWrite)            return "WRITE";
        return "READ";
    }

    /** Short label on the edge: first mapping abbreviated + "(+N)" overflow indicator. */
    private String buildShortLabel(List<FieldMapping> mappings) {
        if (mappings.isEmpty()) return "";
        FieldMapping first     = mappings.get(0);
        String       abbrev    = first.type().name().substring(0, 2);   // AE / CP / PD / MJ
        String       leftField = first.leftSide().isEmpty()  ? "?"
                : first.leftSide().fields().get(0).fieldName();
        String       rightField = first.rightSide().isEmpty() ? "?"
                : first.rightSide().fields().get(0).fieldName();
        String label = abbrev + ": " + leftField + "=" + rightField;
        if (label.length() > 28) label = label.substring(0, 25) + "...";
        int extra = mappings.size() - 1;
        if (extra > 0) label += " (+" + extra + ")";
        // Simplify composition/holding labels (holds → held) to "has"
        if (label.contains("holds") && label.contains("held")) label = "has";
        return label;
    }

    /**
     * Builds tooltip/panel HTML.
     * Columns: 目标字段 | 源端字段 | 代码位置 | 代码块
     * Mappings must already be sorted via {@link RelationDisplaySorter#sortMappings}.
     * Target field cells use HTML rowspan for true merged-cell display.
     */
    private String buildTooltipHtml(String src, String tgt, List<FieldMapping> mappings) {
        // Group by sink field display key, preserving the pre-sorted order
        Map<String, List<FieldMapping>> bySink = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            bySink.computeIfAbsent(formatSinkField(m), k -> new ArrayList<>()).add(m);
        }

        // Deduplicate each sink group by source side
        Map<String, List<FieldMapping>> deduped = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldMapping>> e : bySink.entrySet()) {
            LinkedHashMap<String, FieldMapping> unique = new LinkedHashMap<>();
            for (FieldMapping m : e.getValue()) {
                unique.putIfAbsent(formatSourceDedup(m), m);
            }
            if (!unique.isEmpty()) deduped.put(e.getKey(), new ArrayList<>(unique.values()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='max-width:520px;font-size:12px;line-height:1.6'>");
        sb.append("<b style='font-size:13px'>")
          .append(htmlEscape(src)).append(" \u2192 ").append(htmlEscape(tgt))
          .append("</b>");
        sb.append("<hr style='margin:4px 0;border:none;border-top:1px solid #ddd'>");
        sb.append("<table style='border-collapse:collapse;width:100%'>");
        sb.append("<tr style='background:#f5f7fa'>")
          .append("<th style='padding:3px 6px;text-align:left;white-space:nowrap'>目标字段</th>")
          .append("<th style='padding:3px 6px;text-align:left;white-space:nowrap'>源端字段</th>")
          .append("<th style='padding:3px 6px;text-align:left;white-space:nowrap'>代码位置</th>")
          .append("<th style='padding:3px 6px;text-align:left;white-space:nowrap'>代码块</th>")
          .append("</tr>");

        int rowIdx = 0;
        for (Map.Entry<String, List<FieldMapping>> sinkEntry : deduped.entrySet()) {
            String sinkDisplay = sinkEntry.getKey();
            List<FieldMapping> rows = sinkEntry.getValue();
            int rowspan = rows.size();

            for (int i = 0; i < rows.size(); i++) {
                FieldMapping m  = rows.get(i);
                String       bg = (rowIdx++ % 2 == 0) ? "#fff" : "#f9f9f9";
                String       srcField = formatSourceField(m);
                String       loc      = m.location() != null ? m.location() : "";
                String       code     = m.rawExpression() != null ? m.rawExpression() : "";

                sb.append("<tr style='background:").append(bg).append("'>");
                if (i == 0) {
                    sb.append("<td rowspan='").append(rowspan)
                      .append("' style='padding:3px 6px;font-family:monospace;")
                      .append("vertical-align:middle;border-right:1px solid #eee;font-weight:600'>")
                      .append(htmlEscape(sinkDisplay)).append("</td>");
                }
                sb.append("<td style='padding:3px 6px;font-family:monospace'>")
                  .append(htmlEscape(srcField)).append("</td>")
                  .append("<td style='padding:3px 6px;color:#666;font-size:11px;white-space:nowrap'>")
                  .append(htmlEscape(loc)).append("</td>")
                  .append("<td style='padding:3px 6px;font-family:monospace;color:#555;max-width:50ch;")
                  .append("word-break:break-all;white-space:normal'>")
                  .append(htmlEscape(code)).append("</td>")
                  .append("</tr>");
            }
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    /**
     * Deduplicates mappings by (sinkField, sourceField) — mirrors the dedup logic
     * in {@link #buildTooltipHtml} so that counts are consistent with what is displayed.
     */
    private List<FieldMapping> deduplicateMappings(List<FieldMapping> mappings) {
        Map<String, List<FieldMapping>> bySink = new LinkedHashMap<>();
        for (FieldMapping m : mappings) {
            bySink.computeIfAbsent(formatSinkField(m), k -> new ArrayList<>()).add(m);
        }
        List<FieldMapping> result = new ArrayList<>();
        for (List<FieldMapping> group : bySink.values()) {
            LinkedHashMap<String, FieldMapping> unique = new LinkedHashMap<>();
            for (FieldMapping m : group) {
                unique.putIfAbsent(formatSourceDedup(m), m);
            }
            result.addAll(unique.values());
        }
        return result;
    }

    private String formatSinkField(FieldMapping m) {
        if (m.rightSide() == null || m.rightSide().isEmpty()) return "?";
        return m.rightSide().fields().stream()
                .map(FieldRef::fieldName)
                .sorted()
                .reduce((a, b) -> a + ", " + b).orElse("?");
    }

    private String formatSourceField(FieldMapping m) {
        if (m.leftSide() == null || m.leftSide().isEmpty()) return "?";
        return m.leftSide().fields().stream()
                .map(f -> ClassNameValidator.extractSimpleName(f.className()) + "." + f.fieldName())
                .reduce((a, b) -> a + ", " + b).orElse("?");
    }

    private String formatSourceDedup(FieldMapping m) {
        if (m.leftSide() == null || m.leftSide().isEmpty()) return "?";
        return m.leftSide().fields().stream()
                .map(f -> ClassNameValidator.extractSimpleName(f.className()) + "." + f.fieldName())
                .sorted()
                .reduce((a, b) -> a + "," + b).orElse("?");
    }

    // -------------------------------------------------------------------------
    // HTML template  — uses placeholder substitution to avoid text-block issues
    // -------------------------------------------------------------------------

    private String renderHtml(String projectName,
                               List<Map<String, Object>> nodes,
                               List<Map<String, Object>> edges) {
        String nodesJson = toJsonArray(nodes);
        String edgesJson = toJsonArray(edges);
        String titleSafe = htmlEscape(projectName);
        String nodeCount = String.valueOf(nodes.size());
        String edgeCount = String.valueOf(edges.size());

        String template = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>__TITLE__ \u2014 Field Lineage</title>
  <script src="https://unpkg.com/@antv/g6@5.0.50/dist/g6.min.js"></script>
  <style>
    *, *::before, *::after { margin:0; padding:0; box-sizing:border-box; }
    body { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,sans-serif;
           background:#0d1117; color:#c9d1d9; overflow:hidden; }

    /* ── Header ── */
    #header {
      position:fixed; top:0; left:0; right:0; height:52px; z-index:200;
      background:#161b22; border-bottom:1px solid #30363d;
      display:flex; align-items:center; gap:12px; padding:0 16px;
    }
    #header h1 { font-size:14px; font-weight:600; color:#e6edf3; white-space:nowrap; }
    #search-wrap {
      flex:1; max-width:280px;
      display:flex; align-items:center; gap:6px;
      background:#0d1117; border:1px solid #30363d; border-radius:6px;
      padding:0 10px; height:30px;
    }
    #search-wrap svg { color:#8b949e; flex-shrink:0; }
    #search-input {
      border:none; outline:none; background:transparent;
      color:#c9d1d9; font-size:12px; width:100%;
    }
    #search-input::placeholder { color:#484f58; }
    .hdr-btn {
      padding:4px 10px; font-size:12px; border-radius:5px; cursor:pointer;
      border:1px solid #30363d; background:#21262d; color:#c9d1d9;
      transition:background 0.15s;
    }
    .hdr-btn:hover { background:#30363d; }
    .hdr-btn.danger { border-color:#da3633; color:#f85149; }
    .hdr-btn.danger:hover { background:#3d1a1a; }
    #hdr-meta { font-size:11px; color:#484f58; white-space:nowrap; margin-left:auto; }

    /* ── Canvas ── */
    #container { position:fixed; top:52px; left:0; right:0; bottom:0; }

    /* ── Explore banner ── */
    #explore-banner {
      display:none; position:fixed; top:60px; left:50%; transform:translateX(-50%);
      background:#1f2937; border:1px solid #374151; border-radius:20px;
      padding:6px 14px; font-size:12px; color:#9ca3af; z-index:300;
      align-items:center; gap:10px; box-shadow:0 4px 12px rgba(0,0,0,0.4);
    }
    #explore-banner b { color:#60a5fa; }
    #btn-exit-explore {
      cursor:pointer; background:#374151; border:none; color:#d1d5db;
      border-radius:4px; padding:2px 8px; font-size:11px;
    }
    #btn-exit-explore:hover { background:#4b5563; }

    /* ── Sidebar ── */
    #sidebar {
      position:fixed; top:52px; right:-100vw;
      width:fit-content; min-width:340px; max-width:80vw;
      height:calc(100vh - 52px); background:#161b22;
      border-left:1px solid #30363d; z-index:150;
      transition:right 0.22s ease; display:flex; flex-direction:column;
      overflow:hidden;
    }
    #sidebar.open { right:0; }
    #sidebar-head {
      display:flex; align-items:center; justify-content:space-between;
      padding:12px 14px 10px; border-bottom:1px solid #21262d; flex-shrink:0;
    }
    #sidebar-title { font-size:12px; font-weight:600; color:#8b949e;
                     text-transform:uppercase; letter-spacing:0.6px; }
    #btn-close-panel {
      cursor:pointer; background:none; border:none; color:#484f58;
      font-size:18px; line-height:1; padding:2px 4px;
    }
    #btn-close-panel:hover { color:#c9d1d9; }
    #panel-scroll { flex:1; overflow-y:auto; padding:0 0 16px; }
    #panel-scroll::-webkit-scrollbar { width:4px; }
    #panel-scroll::-webkit-scrollbar-track { background:#0d1117; }
    #panel-scroll::-webkit-scrollbar-thumb { background:#30363d; border-radius:2px; }

    /* Panel typography */
    .p-node-name { font-size:18px; font-weight:700; color:#e6edf3; padding:14px 14px 2px; line-height:1.2; }
    .p-fqn {
      font-size:10px; font-family:monospace; color:#8b949e; padding:0 14px 4px;
      word-break:break-all; cursor:pointer; display:flex; align-items:flex-start; gap:4px;
    }
    .p-fqn:hover { color:#60a5fa; }
    .p-pkg { font-size:11px; color:#6e7681; padding:0 14px 10px; }
    .p-stats {
      display:flex; gap:0; margin:0 14px 12px;
      background:#0d1117; border-radius:6px; border:1px solid #21262d;
    }
    .p-stat {
      flex:1; text-align:center; padding:7px 4px;
      font-size:11px; color:#8b949e;
    }
    .p-stat b { display:block; font-size:17px; color:#e6edf3; font-weight:700; }
    .p-stat:not(:last-child) { border-right:1px solid #21262d; }
    .p-explore-btn {
      margin:0 14px 12px; padding:7px; text-align:center; cursor:pointer;
      background:#1c2333; border:1px solid #388bfd30; border-radius:6px;
      font-size:12px; color:#58a6ff; transition:background 0.15s;
    }
    .p-explore-btn:hover { background:#1f3050; }
    .p-section { font-size:10px; font-weight:700; color:#484f58; letter-spacing:0.8px;
                  text-transform:uppercase; padding:8px 14px 4px; }
    .p-edge-item {
      display:flex; align-items:center; gap:7px; padding:6px 14px;
      cursor:pointer; transition:background 0.1s;
    }
    .p-edge-item:hover { background:#161d27; }
    .p-etype {
      font-size:10px; font-weight:700; padding:1px 6px; border-radius:3px;
      flex-shrink:0; font-family:monospace;
    }
    .p-edge-node { font-size:12px; color:#c9d1d9; flex:1; overflow:hidden;
                   text-overflow:ellipsis; white-space:nowrap; }
    .p-edge-label { font-size:10px; color:#484f58; font-family:monospace;
                    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:80px; }
    .p-arrow { color:#484f58; font-size:11px; flex-shrink:0; }
    .p-hint { padding:40px 14px; text-align:center; color:#484f58; font-size:13px; }

    /* Edge detail panel */
    .p-edge-detail { padding:12px 14px; }
    .p-edge-header { font-size:13px; font-weight:600; color:#e6edf3; margin-bottom:10px; }
    .p-edge-table { width:100%; border-collapse:collapse; font-size:11px; }
    .p-edge-table th {
      text-align:left; padding:5px 6px; background:#0d1117; color:#8b949e;
      border-bottom:1px solid #21262d; font-weight:600; font-size:10px; text-transform:uppercase;
    }
    .p-edge-table td { padding:5px 6px; color:#c9d1d9; border-bottom:1px solid #161b22;
                       font-family:monospace; font-size:11px; }
    .p-edge-table tr:hover td { background:#1c2333; }
    .p-loc { padding:2px 6px; color:#484f58; font-size:10px; }

    /* Legend */
    #legend {
      position:fixed; bottom:20px; left:20px; z-index:100;
      background:#161b22; border:1px solid #30363d; border-radius:8px;
      padding:10px 14px; font-size:11px; line-height:2;
    }
    #legend h4 { font-size:10px; font-weight:700; color:#484f58;
                  text-transform:uppercase; letter-spacing:0.6px; margin-bottom:4px; }
    .leg-row { display:flex; align-items:center; gap:8px; color:#8b949e; }
    .leg-line { width:24px; height:2px; border-radius:1px; flex-shrink:0; }
    .g6-tooltip {
      background:#161b22; border:1px solid #30363d; border-radius:6px;
      box-shadow:0 4px 16px rgba(0,0,0,0.5); padding:10px 12px;
      max-width:420px; pointer-events:none; font-size:12px; color:#c9d1d9;
    }
  </style>
</head>
<body>

<div id="header">
  <h1>__TITLE__</h1>
  <div id="search-wrap">
    <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor">
      <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398l3.85 3.85a1 1 0 0 0 1.415-1.415l-3.868-3.833zm-5.242 1.156a5 5 0 1 1 0-10 5 5 0 0 1 0 10z"/>
    </svg>
    <input id="search-input" placeholder="Search class\u2026" autocomplete="off">
  </div>
  <button class="hdr-btn" onclick="resetView()">Reset</button>
  <button class="hdr-btn" onclick="doFitView()">Fit</button>
  <span id="hdr-meta">__NODE_COUNT__ nodes &nbsp;&middot;&nbsp; __EDGE_COUNT__ edges</span>
</div>

<div id="container"></div>

<div id="explore-banner">
  <span>Exploring: <b id="explore-label"></b></span>
  <button id="btn-exit-explore" onclick="exitExploreMode()">Exit \u00d7</button>
</div>

<div id="sidebar">
  <div id="sidebar-head">
    <span id="sidebar-title">Details</span>
    <button id="btn-close-panel" onclick="closePanel()" title="Close">\u00d7</button>
  </div>
  <div id="panel-scroll">
    <div id="panel-content"><div class="p-hint">Click a node or edge to explore</div></div>
  </div>
</div>

<div id="legend">
  <h4>Edge types</h4>
  <div class="leg-row"><div class="leg-line" style="background:#1976d2"></div>READ</div>
  <div class="leg-row"><div class="leg-line" style="background:#f57c00"></div>WRITE</div>
  <div class="leg-row"><div class="leg-line" style="background:#9c27b0"></div>MIXED</div>
  <div class="leg-row"><div class="leg-line" style="background:#388e3c;
    background:repeating-linear-gradient(90deg,#388e3c 0,#388e3c 5px,transparent 5px,transparent 9px)">
  </div>INHERIT</div>
</div>

<script>
  const NODES = __NODES_JSON__;
  const EDGES = __EDGES_JSON__;

  // ── Index structures ────────────────────────────────────────────────────────
  const nodeById = Object.fromEntries(NODES.map(n => [n.id, n]));
  const edgeById = Object.fromEntries(EDGES.map(e => [e.id, e]));
  const adjOut   = Object.fromEntries(NODES.map(n => [n.id, []]));
  const adjIn    = Object.fromEntries(NODES.map(n => [n.id, []]));
  EDGES.forEach(e => {
    if (adjOut[e.source]) adjOut[e.source].push(e);
    if (adjIn[e.target])  adjIn[e.target].push(e);
  });

  function neighborIds(nid) {
    const s = new Set();
    (adjOut[nid]||[]).forEach(e => s.add(e.target));
    (adjIn[nid] ||[]).forEach(e => s.add(e.source));
    return s;
  }
  function connEdgeIds(nid) {
    const s = new Set();
    (adjOut[nid]||[]).forEach(e => s.add(e.id));
    (adjIn[nid] ||[]).forEach(e => s.add(e.id));
    return s;
  }

  // ── Visual state (read by style functions) ──────────────────────────────────
  // nodeVis[id]: '' | 'inactive' | 'selected' | 'searched' | 'ghost'
  // edgeVis[id]: '' | 'inactive' | 'ghost'
  const nodeVis = Object.fromEntries(NODES.map(n => [n.id, '']));
  const edgeVis = Object.fromEntries(EDGES.map(e => [e.id, '']));

  // ── Colors ──────────────────────────────────────────────────────────────────
  const PALETTE = [
    '#4C8EDA','#E07B39','#4BAD8C','#8E5FB9',
    '#D4A838','#5ABBD6','#D45F4C','#5A6E8C',
    '#2E8FBF','#B09E5E',
  ];
  const EDGE_COLORS = { READ:'#1976d2', WRITE:'#f57c00', MIXED:'#9c27b0', INHERIT:'#388e3c' };

  // ── Layout helpers (same as before) ─────────────────────────────────────────
  const LAYER_SEP = 160, NODE_SEP = 90, COMP_GAP = 120;

  function groupByComponent(nodes, edges) {
    const groups = {}, nodeComp = {};
    nodes.forEach(n => {
      const cid = String(n.data.componentId);
      nodeComp[n.id] = cid;
      if (!groups[cid]) groups[cid] = { nodeIds:[], edges:[] };
      groups[cid].nodeIds.push(n.id);
    });
    edges.forEach(e => {
      const cid = nodeComp[e.source];
      if (cid && groups[cid]) groups[cid].edges.push(e);
    });
    return groups;
  }
  function assignLayers(nodeIds, edges) {
    const inDeg={}, outAdj={}, nodeSet=new Set(nodeIds);
    nodeIds.forEach(id => { inDeg[id]=0; outAdj[id]=[]; });
    edges.forEach(e => {
      if (!nodeSet.has(e.source)||!nodeSet.has(e.target)||e.source===e.target) return;
      outAdj[e.source].push(e.target); inDeg[e.target]++;
    });
    const layer={}; nodeIds.forEach(id => { layer[id]=0; });
    const queue=nodeIds.filter(id=>inDeg[id]===0); let head=0;
    while (head<queue.length) {
      const cur=queue[head++];
      (outAdj[cur]||[]).forEach(next => {
        layer[next]=Math.max(layer[next]||0,(layer[cur]||0)+1);
        if (--inDeg[next]===0) queue.push(next);
      });
    }
    return layer;
  }
  function orderLayer(ids, li, edges, layerMap) {
    if (li===0||ids.length<=1) return ids;
    const prevPos={};
    Object.entries(layerMap).filter(([,l])=>l===li-1).forEach(([id],i)=>{ prevPos[id]=i; });
    const bary={};
    ids.forEach(id => {
      const nbrs = edges
        .filter(e=>(e.target===id&&prevPos[e.source]!==undefined)||(e.source===id&&prevPos[e.target]!==undefined))
        .map(e=>e.source===id?prevPos[e.target]:prevPos[e.source]);
      bary[id]=nbrs.length?nbrs.reduce((s,v)=>s+v,0)/nbrs.length:1e9;
    });
    return [...ids].sort((a,b)=>bary[a]-bary[b]);
  }
  function packComponents(groups, allLayers) {
    const cids = Object.keys(groups).sort((a,b)=>groups[b].nodeIds.length-groups[a].nodeIds.length);
    const offsets={}; let curY=0;
    cids.forEach(cid => {
      offsets[cid]={offsetX:0,offsetY:curY};
      const byLayer={};
      groups[cid].nodeIds.forEach(id=>{ const l=allLayers[cid][id]||0; byLayer[l]=(byLayer[l]||0)+1; });
      curY+=(Math.max(...Object.values(byLayer),1)-1)*NODE_SEP+NODE_SEP+COMP_GAP;
    });
    return { offsets, orderedIds:cids };
  }
  function computeGridColors(orderedIds, palette) {
    const cidx=new Array(orderedIds.length).fill(null);
    for (let i=0;i<orderedIds.length;i++) {
      const used=new Set();
      if (i>0&&cidx[i-1]!=null) used.add(cidx[i-1]);
      for (let c=0;c<palette.length;c++) { if (!used.has(c)){cidx[i]=c;break;} }
      if (cidx[i]==null) cidx[i]=0;
    }
    const map={}; orderedIds.forEach((cid,i)=>{ map[cid]=cidx[i]; }); return map;
  }

  const componentIds = [...new Set(NODES.map(n=>n.data.componentId))];
  const multiComp    = componentIds.length > 1;
  if (multiComp) NODES.forEach(n=>{ n.combo=String(n.data.componentId); });
  const combos = multiComp ? componentIds.map(id=>({id:String(id),data:{}})) : [];

  let gridColors = null;
  if (multiComp) {
    const groups=groupByComponent(NODES,EDGES), allLayers={};
    Object.keys(groups).forEach(cid=>{ allLayers[cid]=assignLayers(groups[cid].nodeIds,groups[cid].edges); });
    const { offsets, orderedIds } = packComponents(groups, allLayers);
    gridColors = computeGridColors(orderedIds, PALETTE);
    orderedIds.forEach(cid => {
      const { nodeIds, edges } = groups[cid];
      const layerMap=allLayers[cid], { offsetX, offsetY }=offsets[cid];
      const byLayer={};
      nodeIds.forEach(id=>{ const l=layerMap[id]||0; if(!byLayer[l]) byLayer[l]=[]; byLayer[l].push(id); });
      Object.keys(byLayer).sort((a,b)=>Number(a)-Number(b))
        .forEach(l=>{ byLayer[l]=orderLayer(byLayer[l],Number(l),edges,layerMap); });
      Object.entries(byLayer).forEach(([l,ids]) => {
        const totalH=(ids.length-1)*NODE_SEP;
        ids.forEach((id,i)=>{
          const node=NODES.find(n=>n.id===id); if(!node) return;
          node.style=node.style||{};
          node.style.x=offsetX+Number(l)*LAYER_SEP+80;
          node.style.y=offsetY+i*NODE_SEP-totalH/2+NODE_SEP/2;
        });
      });
    });
  }

  function measureLabel(text) { return (text||'').length*9+28; }
  function findCenterId(nodes, edges) {
    const deg={};
    nodes.forEach(n=>{deg[n.id]=0;});
    edges.forEach(e=>{deg[e.source]=(deg[e.source]||0)+1;deg[e.target]=(deg[e.target]||0)+1;});
    return nodes.reduce((best,n)=>(deg[n.id]||0)>=(deg[best.id]||0)?n:best,nodes[0]).id;
  }
  const focusNodeId = !multiComp && NODES.length ? findCenterId(NODES,EDGES) : undefined;
  const layoutConfig = multiComp ? { type:'preset' } : {
    type:'radial', focusNode:focusNodeId, unitRadius:160, linkDistance:200,
    preventOverlap:true, nodeSize:64, nodeSpacing:24, strictRadial:false, maxPreventOverlapIteration:300,
  };

  // ── Interaction state ────────────────────────────────────────────────────────
  let activeNode   = null;
  let exploreMode  = false;
  let graphInst    = null;

  // ── Style helpers ────────────────────────────────────────────────────────────
  function nodeStyleFn(model) {
    const vis      = nodeVis[model.id] || '';
    const ghost    = vis === 'ghost';
    const inactive = vis === 'inactive';
    const searched = vis === 'searched';
    const selected = vis === 'selected';
    const isParent = model.data && model.data.nodeType === 'parent';
    const cid      = String((model.data && model.data.componentId) || 0);
    const pidx     = gridColors ? (gridColors[cid] ?? 0) : (parseInt(cid)||0);
    const baseColor = PALETTE[pidx % PALETTE.length];
    const w        = measureLabel(model.id);
    return {
      size:           [w, 28],
      radius:         10,
      fill:           ghost || inactive ? '#1c2333' : baseColor,
      fillOpacity:    ghost ? 0.08 : (inactive ? 0.25 : 1),
      stroke:         selected ? '#f0b429' : (searched ? '#ff9800' : (isParent ? '#4caf50' : baseColor)),
      lineWidth:      selected ? 2.5 : (searched ? 2.5 : (isParent ? 2 : 1.2)),
      shadowBlur:     ghost || inactive ? 0 : (selected ? 16 : 6),
      shadowColor:    selected ? 'rgba(240,180,41,0.4)' : 'rgba(0,0,0,0.35)',
      cursor:         ghost ? 'default' : 'pointer',
      labelText:      model.id,
      labelFill:      ghost ? '#2a3545' : (inactive ? '#3d4f66' : '#e6edf3'),
      labelFontSize:  12,
      labelFontWeight:'600',
      labelPlacement: 'center',
      labelWordWrap:  false,
    };
  }

  function edgeStyleFn(model) {
    const vis     = edgeVis[model.id] || '';
    const ghost   = vis === 'ghost';
    const inactive= vis === 'inactive';
    const type    = (model.data && model.data.edgeType) || 'READ';
    const baseColor = EDGE_COLORS[type] || '#999';
    const color   = ghost || inactive ? '#252d3a' : baseColor;
    return {
      stroke:       color,
      opacity:      ghost ? 0.04 : (inactive ? 0.12 : 1),
      lineWidth:    type === 'MIXED' ? 2.5 : 1.8,
      lineDash:     type === 'INHERIT' ? [7,4] : undefined,
      endArrow:     true,
      endArrowFill: color,
      endArrowSize: 9,
      cursor:       ghost ? 'default' : 'pointer',
      labelText:    ghost || inactive ? '' : ((model.data && model.data.shortLabel) || ''),
      labelFontSize:10,
      labelFill:    color,
      labelBackgroundFill: 'rgba(13,17,23,0.9)',
      labelBackgroundPadding:[2,5,2,5],
      labelBackgroundRadius:3,
      labelOffsetY: -8,
    };
  }

  // ── State update + redraw ────────────────────────────────────────────────────
  function applyVis() {
    if (!graphInst) return;
    NODES.forEach(n => graphInst.updateNodeData([{ id:n.id }]));
    EDGES.forEach(e => graphInst.updateEdgeData([{ id:e.id }]));
    graphInst.draw();
  }

  // ── Highlight: dim non-connected, keep node+neighbors bright ────────────────
  function highlightNode(nid) {
    activeNode = nid;
    const nbrs  = neighborIds(nid);
    const cedges = connEdgeIds(nid);
    NODES.forEach(n => { nodeVis[n.id] = (n.id===nid) ? 'selected' : (nbrs.has(n.id) ? '' : 'inactive'); });
    EDGES.forEach(e => { edgeVis[e.id] = cedges.has(e.id) ? '' : 'inactive'; });
    applyVis();
    showNodePanel(nid);
  }

  // ── Explore mode: ghost non-neighborhood elements ────────────────────────────
  function enterExploreMode(nid) {
    exploreMode = true;
    activeNode  = nid;
    const nbrs  = neighborIds(nid);
    const vis   = new Set([nid, ...nbrs]);
    const cedges = connEdgeIds(nid);
    NODES.forEach(n => { nodeVis[n.id] = vis.has(n.id) ? (n.id===nid ? 'selected' : '') : 'ghost'; });
    EDGES.forEach(e => { edgeVis[e.id] = cedges.has(e.id) ? '' : 'ghost'; });
    applyVis();
    document.getElementById('explore-label').textContent = nid;
    document.getElementById('explore-banner').style.display = 'flex';
    showNodePanel(nid);
    setTimeout(() => graphInst && graphInst.fitView({ padding:80 }), 120);
  }

  function exitExploreMode() {
    exploreMode = false;
    if (activeNode) {
      highlightNode(activeNode);
    } else {
      clearAll();
    }
    document.getElementById('explore-banner').style.display = 'none';
  }

  // ── Clear all visual state ───────────────────────────────────────────────────
  function clearAll() {
    activeNode = null;
    NODES.forEach(n => { nodeVis[n.id]=''; });
    EDGES.forEach(e => { edgeVis[e.id]=''; });
    applyVis();
    closePanel();
  }

  function resetView() {
    exploreMode = false;
    clearSearchUI();
    clearAll();
    document.getElementById('explore-banner').style.display = 'none';
    setTimeout(()=>graphInst&&graphInst.fitView({padding:60}),60);
  }

  function doFitView() { graphInst && graphInst.fitView({padding:60}); }

  // ── Search ───────────────────────────────────────────────────────────────────
  function doSearch(q) {
    const term = (q||'').trim().toLowerCase();
    if (!term) { clearSearchUI(); return; }
    let hitCount = 0;
    NODES.forEach(n => {
      const match = n.id.toLowerCase().includes(term) ||
                    (n.data.fqn||'').toLowerCase().includes(term);
      if (match) { nodeVis[n.id]='searched'; hitCount++; }
      else if (nodeVis[n.id]==='searched') nodeVis[n.id]='';
    });
    applyVis();
  }
  function clearSearchUI() {
    document.getElementById('search-input').value='';
    const changed = NODES.some(n=>nodeVis[n.id]==='searched');
    if (changed) { NODES.forEach(n=>{ if(nodeVis[n.id]==='searched') nodeVis[n.id]=''; }); applyVis(); }
  }

  // ── Panel: node detail ───────────────────────────────────────────────────────
  function esc(s) {
    return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function showNodePanel(nid) {
    const node = nodeById[nid]; if(!node) return;
    const fqn = node.data.fqn || nid;
    const lastDot = fqn.lastIndexOf('.');
    const pkg = lastDot>0 ? fqn.substring(0,lastDot) : '(default package)';
    const outs = adjOut[nid]||[], ins = adjIn[nid]||[];

    const etypeBadge = (type, label) => {
      const c = {READ:'#1976d2',WRITE:'#f57c00',MIXED:'#9c27b0',INHERIT:'#388e3c'}[type]||'#555';
      return `<span class="p-etype" style="background:${c}20;color:${c};border:1px solid ${c}40">${esc(label||type)}</span>`;
    };

    const edgeRow = (e, otherId, isOut) => {
      const d = e.data||{};
      return `<div class="p-edge-item" onclick="navigateTo('${esc(otherId)}')">
        ${etypeBadge(d.edgeType)}
        <span class="p-arrow">${isOut?'\u2192':'\u2190'}</span>
        <span class="p-edge-node" title="${esc(otherId)}">${esc(otherId)}</span>
        ${d.shortLabel?`<span class="p-edge-label" title="${esc(d.shortLabel)}">${esc(d.shortLabel)}</span>`:''}
      </div>`;
    };

    let html = `
      <div class="p-node-name">${esc(nid)}</div>
      <div class="p-fqn" onclick="copyText('${esc(fqn)}')" title="Click to copy">
        <span>${esc(fqn)}</span>
        <svg width="10" height="10" viewBox="0 0 16 16" fill="currentColor" style="flex-shrink:0;margin-top:1px">
          <path d="M0 6.75C0 5.784.784 5 1.75 5h1.5a.75.75 0 0 1 0 1.5h-1.5a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-1.5a.75.75 0 0 1 1.5 0v1.5A1.75 1.75 0 0 1 9.25 16h-7.5A1.75 1.75 0 0 1 0 14.25Z"/>
          <path d="M5 1.75C5 .784 5.784 0 6.75 0h7.5C15.216 0 16 .784 16 1.75v7.5A1.75 1.75 0 0 1 14.25 11h-7.5A1.75 1.75 0 0 1 5 9.25Zm1.75-.25a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-7.5a.25.25 0 0 0-.25-.25Z"/>
        </svg>
      </div>
      <div class="p-pkg">${esc(pkg)}</div>
      <div class="p-stats">
        <div class="p-stat"><b>${outs.length}</b>out</div>
        <div class="p-stat"><b>${ins.length}</b>in</div>
        <div class="p-stat"><b>${outs.length+ins.length}</b>total</div>
      </div>
      <div class="p-explore-btn" onclick="enterExploreMode('${esc(nid)}')">
        🔍 Explore neighborhood
      </div>`;

    if (outs.length>0) {
      html += `<div class="p-section">Outgoing (${outs.length})</div>`;
      outs.forEach(e => { html += edgeRow(e, e.target, true); });
    }
    if (ins.length>0) {
      html += `<div class="p-section">Incoming (${ins.length})</div>`;
      ins.forEach(e => { html += edgeRow(e, e.source, false); });
    }

    document.getElementById('panel-content').innerHTML = html;
    document.getElementById('sidebar-title').textContent = 'Node';
    document.getElementById('sidebar').classList.add('open');
  }

  // ── Panel: edge detail ───────────────────────────────────────────────────────
  function showEdgePanel(eid) {
    const edge = edgeById[eid]; if(!edge) return;
    const d = edge.data||{};
    let html = `<div class="p-edge-detail">
      <div class="p-edge-header">${esc(edge.source)} \u2192 ${esc(edge.target)}</div>`;
    if (d.tooltipHtml) {
      html += `<div style="font-size:11px;color:#8b949e">${d.tooltipHtml}</div>`;
    }
    html += '</div>';
    document.getElementById('panel-content').innerHTML = html;
    document.getElementById('sidebar-title').textContent = 'Edge';
    document.getElementById('sidebar').classList.add('open');
  }

  function closePanel() {
    document.getElementById('sidebar').classList.remove('open');
    if (!exploreMode) { NODES.forEach(n=>{if(nodeVis[n.id]==='selected')nodeVis[n.id]='';});applyVis(); }
    activeNode = exploreMode ? activeNode : null;
  }

  function navigateTo(nid) {
    if (exploreMode) exitExploreMode();
    highlightNode(nid);
  }

  function copyText(text) {
    navigator.clipboard && navigator.clipboard.writeText(text)
      .then(()=>showToast('Copied!'))
      .catch(()=>{});
  }

  function showToast(msg) {
    let t = document.getElementById('_toast');
    if (!t) {
      t = document.createElement('div');
      t.id = '_toast';
      Object.assign(t.style, {
        position:'fixed',bottom:'80px',left:'50%',transform:'translateX(-50%)',
        background:'#238636',color:'#fff',padding:'6px 16px',borderRadius:'6px',
        fontSize:'12px',zIndex:'500',pointerEvents:'none',transition:'opacity 0.3s',
      });
      document.body.appendChild(t);
    }
    t.textContent = msg; t.style.opacity = '1';
    clearTimeout(t._timer);
    t._timer = setTimeout(()=>{ t.style.opacity='0'; }, 1500);
  }

  // ── Bootstrap G6 ─────────────────────────────────────────────────────────────
  (async () => {
    graphInst = new G6.Graph({
      container: 'container',
      data: { nodes: NODES, edges: EDGES, combos },
      layout: layoutConfig,
      node: { type:'rect', style: nodeStyleFn },
      edge: { style: edgeStyleFn },
      combo: {
        style: (model) => {
          const pidx = gridColors ? (gridColors[model.id]??0) : 0;
          const color = PALETTE[pidx % PALETTE.length];
          return { fill:color, fillOpacity:0.06, stroke:color, strokeOpacity:0.35,
                   lineWidth:1.5, radius:14, lineDash:[5,3], labelText:'' };
        },
      },
      behaviors: ['zoom-canvas','drag-canvas','drag-element'],
      plugins: [{
        type: 'tooltip',
        getContent: (_evt, items) => {
          if (!items||!items.length) return '';
          const item = items[0];
          if (item.data && item.data.tooltipHtml)
            return '<div class="g6-tooltip">' + item.data.tooltipHtml + '</div>';
          const fqn = item.data && item.data.fqn ? item.data.fqn : item.id;
          return `<div class="g6-tooltip"><b style="color:#e6edf3">${esc(item.id)}</b>` +
                 (fqn !== item.id ? `<div style="color:#8b949e;font-size:10px;font-family:monospace;margin-top:3px">${esc(fqn)}</div>` : '') +
                 '</div>';
        },
      }],
    });

    await graphInst.render();

    // ── Events ────────────────────────────────────────────────────────────────
    graphInst.on('node:click', (evt) => {
      const nid = evt.itemId || (evt.target && evt.target.id);
      if (nid && nodeById[nid]) {
        if (exploreMode) { exitExploreMode(); setTimeout(()=>highlightNode(nid),50); }
        else highlightNode(nid);
      }
    });

    graphInst.on('node:dblclick', (evt) => {
      const nid = evt.itemId || (evt.target && evt.target.id);
      if (nid && nodeById[nid]) enterExploreMode(nid);
    });

    graphInst.on('edge:click', (evt) => {
      const eid = evt.itemId || (evt.target && evt.target.id);
      if (eid && edgeById[eid]) showEdgePanel(eid);
    });

    graphInst.on('canvas:click', () => {
      if (!exploreMode) clearAll();
      clearSearchUI();
    });

    setTimeout(()=>graphInst.fitView({padding:60}), 200);
  })();

  // ── Search input ─────────────────────────────────────────────────────────────
  document.getElementById('search-input').addEventListener('input', e => doSearch(e.target.value));
  document.getElementById('search-input').addEventListener('keydown', e => {
    if (e.key==='Escape') { clearSearchUI(); e.target.blur(); }
  });

  // ── Keyboard shortcuts ────────────────────────────────────────────────────────
  document.addEventListener('keydown', e => {
    if (e.key==='Escape') {
      if (exploreMode) { exitExploreMode(); return; }
      if (document.getElementById('sidebar').classList.contains('open')) { closePanel(); return; }
      clearAll();
    }
    if ((e.ctrlKey||e.metaKey) && e.key==='f') {
      e.preventDefault(); document.getElementById('search-input').focus();
    }
  });
</script>
</body>
</html>
""";

        return template
                .replace("__TITLE__",      titleSafe)
                .replace("__NODE_COUNT__", nodeCount)
                .replace("__EDGE_COUNT__", edgeCount)
                .replace("__NODES_JSON__", nodesJson)
                .replace("__EDGES_JSON__", edgesJson);
    }

    // -------------------------------------------------------------------------
    // Minimal JSON serializer  (no external dependency required)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String toJsonArray(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toJsonObject(items.get(i)));
        }
        return sb.append("]").toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val instanceof Map<?,?> m) {
                sb.append(toJsonObject((Map<String, Object>) m));
            } else if (val instanceof List<?> l) {
                sb.append(toJsonArray((List<Map<String, Object>>) l));
            } else if (val instanceof Boolean || val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(jsonEscape(String.valueOf(val))).append("\"");
            }
        }
        return sb.append("}").toString();
    }

    /**
     * Escapes a string value for safe embedding inside a JSON string
     * that is itself embedded in an HTML {@code <script>} block.
     * Unicode-escapes {@code < > &} to prevent HTML parser injection.
     */
    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("<",  "\\u003c")
                .replace(">",  "\\u003e")
                .replace("&",  "\\u0026");
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // -------------------------------------------------------------------------
    // New methods: Export JSON and config files for external data source mode
    // -------------------------------------------------------------------------

    /**
     * 导出图数据为 JSON 文件（不包含 HTML）。
     * 生成的 JSON 可被 graph-template.html 通过 datasource-config.json 引用。
     *
     * @param relations 类关系列表
     * @return JSON 字符串
     */
    public String exportJson(List<ClassRelation> relations) {
        Set<String> nodeIds = collectNodeIds(relations);
        Map<String, Integer> componentMap = computeComponents(nodeIds, relations);
        List<Map<String, Object>> nodes = buildNodes(relations, componentMap);
        List<Map<String, Object>> edges = buildEdges(relations);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("edges", edges);

        return toJsonObject(data);
    }

    /**
     * 生成数据源配置文件内容。
     *
     * @param projectName 项目名称
     * @param dataFileName 数据文件名（如 "data.json"）
     * @param nodeCount 节点数量
     * @param edgeCount 边数量
     * @return JSON 格式的配置文件内容
     */
    public String generateDataSourceConfig(String projectName, String dataFileName,
                                            int nodeCount, int edgeCount) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0");

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "file");
        dataSource.put("path", dataFileName);
        dataSource.put("description", "Class relations analysis result");
        config.put("dataSource", dataSource);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectName", projectName);
        metadata.put("generatedAt", Instant.now().toString());
        metadata.put("nodeCount", nodeCount);
        metadata.put("edgeCount", edgeCount);
        metadata.put("description", "Auto-generated by AntVG6HtmlRenderer");
        config.put("metadata", metadata);

        return toJsonObject(config);
    }

    /**
     * 保存 JSON 数据到文件。
     *
     * @param relations 类关系列表
     * @param outputPath 输出文件路径
     * @throws IOException 文件写入异常
     */
    public void saveAsJson(List<ClassRelation> relations, String outputPath) throws IOException {
        String json = exportJson(relations);
        try (FileWriter writer = new FileWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    /**
     * 保存数据源配置文件。
     *
     * @param projectName 项目名称
     * @param dataFileName 数据文件名
     * @param nodeCount 节点数量
     * @param edgeCount 边数量
     * @param outputPath 输出文件路径
     * @throws IOException 文件写入异常
     */
    public void saveDataSourceConfig(String projectName, String dataFileName,
                                      int nodeCount, int edgeCount,
                                      String outputPath) throws IOException {
        String config = generateDataSourceConfig(projectName, dataFileName, nodeCount, edgeCount);
        try (FileWriter writer = new FileWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(config);
        }
    }

    /**
     * 从资源文件读取 HTML 模板。
     *
     * @return HTML 模板内容
     * @throws RuntimeException 读取失败时抛出
     */
    private String loadHtmlTemplate() {
        try (InputStream is = getClass().getResourceAsStream("/templates/graph-template.html")) {
            if (is == null) {
                throw new RuntimeException("Template file not found: /templates/graph-template.html");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load HTML template", e);
        }
    }

    /**
     * 渲染 HTML（使用外部数据源配置模式）。
     * 生成的 HTML 会从 datasource-config.json 加载配置，再加载实际数据。
     *
     * @param projectName 项目名称
     * @return 完整的 HTML 内容
     */
    public String renderWithExternalConfig(String projectName) {
        String template = loadHtmlTemplate();
        String titleSafe = htmlEscape(projectName);
        return template.replace("Class Relations Graph", titleSafe + " — Field Lineage");
    }
}
