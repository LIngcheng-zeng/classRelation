package org.example.renderer;

import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.MappingMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Set<String> nodeIds = collectNodeIds(relations);
        Map<String, Integer> componentMap = computeComponents(nodeIds, relations);
        List<Map<String, Object>> nodes = buildNodes(relations, componentMap);
        List<Map<String, Object>> edges = buildEdges(relations);
        return renderHtml(projectName, nodes, edges);
    }

    // -------------------------------------------------------------------------
    // Node construction
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> buildNodes(List<ClassRelation> relations, Map<String, Integer> componentMap) {
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> parents = new LinkedHashSet<>();

        for (ClassRelation rel : relations) {
            nodeIds.add(rel.simpleSourceClass());
            nodeIds.add(rel.simpleTargetClass());
            if (rel.inheritance() != null) {
                nodeIds.add(rel.inheritance().simpleChildClass());
                nodeIds.add(rel.inheritance().simpleParentClass());
                parents.add(rel.inheritance().simpleParentClass());
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String id : nodeIds) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nodeType", parents.contains(id) ? "parent" : "normal");
            data.put("componentId", componentMap.getOrDefault(id, 0));

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
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
            ids.add(rel.simpleSourceClass());
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

            String edgeType   = resolveEdgeType(mappings);
            String shortLabel = buildShortLabel(mappings);
            String tooltip    = buildTooltipHtml(src, tgt, mappings);

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
        return label;
    }

    /** Full HTML table for tooltip display (will be JSON-encoded). */
    private String buildTooltipHtml(String src, String tgt, List<FieldMapping> mappings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='max-width:400px;font-size:12px;line-height:1.6'>");
        sb.append("<b style='font-size:13px'>")
          .append(htmlEscape(src)).append(" -&gt; ").append(htmlEscape(tgt))
          .append("</b>");
        sb.append("<hr style='margin:4px 0;border:none;border-top:1px solid #ddd'>");
        sb.append("<table style='border-collapse:collapse;width:100%'>");
        sb.append("<tr style='background:#f5f7fa'>")
          .append("<th style='padding:3px 6px;text-align:left'>Source field</th>")
          .append("<th style='padding:3px 6px;text-align:left'>Target field</th>")
          .append("<th style='padding:3px 6px;text-align:left'>Type</th>")
          .append("<th style='padding:3px 6px;text-align:left'>Mode</th>")
          .append("</tr>");

        for (int i = 0; i < mappings.size(); i++) {
            FieldMapping m  = mappings.get(i);
            String       bg = (i % 2 == 0) ? "#fff" : "#f9f9f9";

            String srcField = m.leftSide().isEmpty() ? "?"
                    : m.leftSide().fields().stream()
                        .map(f -> {
                            String cn     = f.className();
                            String simple = cn.contains(".")
                                    ? cn.substring(cn.lastIndexOf('.') + 1) : cn;
                            return simple + "." + f.fieldName();
                        })
                        .reduce((a, b) -> a + ", " + b).orElse("?");
            String tgtField = m.rightSide().isEmpty() ? "?"
                    : m.rightSide().fields().stream()
                        .map(f -> f.fieldName())
                        .reduce((a, b) -> a + ", " + b).orElse("?");
            String modeColor = m.mode() == MappingMode.WRITE_ASSIGNMENT ? "#f57c00" : "#1976d2";
            String modeLabel = m.mode() == MappingMode.WRITE_ASSIGNMENT ? "WRITE" : "READ";

            sb.append("<tr style='background:").append(bg).append("'>")
              .append("<td style='padding:3px 6px;font-family:monospace'>")
              .append(htmlEscape(srcField)).append("</td>")
              .append("<td style='padding:3px 6px;font-family:monospace'>")
              .append(htmlEscape(tgtField)).append("</td>")
              .append("<td style='padding:3px 6px'>").append(m.type().name()).append("</td>")
              .append("<td style='padding:3px 6px;color:").append(modeColor).append("'>")
              .append(modeLabel).append("</td>")
              .append("</tr>");

            if (m.location() != null && !m.location().isEmpty()) {
                sb.append("<tr style='background:").append(bg).append("'>")
                  .append("<td colspan='4' style='padding:1px 6px;color:#999;font-size:11px'>")
                  .append(htmlEscape(m.location())).append("</td></tr>");
            }
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // HTML template  — uses placeholder substitution to avoid text-block issues
    // -------------------------------------------------------------------------

    private String renderHtml(String projectName,
                               List<Map<String, Object>> nodes,
                               List<Map<String, Object>> edges) {
        String nodesJson  = toJsonArray(nodes);
        String edgesJson  = toJsonArray(edges);
        String titleSafe  = htmlEscape(projectName);
        String nodeCount  = String.valueOf(nodes.size());
        String edgeCount  = String.valueOf(edges.size());

        // Placeholders: __XX__ — none of these strings appear in CSS/JS content.
        String template = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>__TITLE__ \u2014 Field Lineage Graph</title>
  <script src="https://unpkg.com/@antv/g6@5.0.50/dist/g6.min.js"></script>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, sans-serif; background: #f0f2f5; }
    #header {
      height: 52px; background: #001529; color: #fff;
      display: flex; align-items: center; justify-content: space-between;
      padding: 0 20px; user-select: none; flex-shrink: 0;
    }
    #header h1 { font-size: 16px; font-weight: 600; letter-spacing: 0.3px; }
    #header .meta { font-size: 12px; color: #8c9dba; }
    #container { width: 100vw; height: calc(100vh - 52px); }
    #legend {
      position: fixed; bottom: 20px; right: 20px; z-index: 10;
      background: rgba(255,255,255,0.96); border-radius: 8px;
      box-shadow: 0 2px 12px rgba(0,0,0,0.13); padding: 12px 16px;
      font-size: 12px; line-height: 1.9; min-width: 140px;
    }
    #legend h4 { font-size: 12px; font-weight: 700; margin-bottom: 6px; color: #333; text-transform: uppercase; letter-spacing: 0.5px; }
    .legend-item { display: flex; align-items: center; gap: 8px; color: #555; }
    .legend-line { width: 28px; height: 3px; border-radius: 2px; flex-shrink: 0; }
    .legend-dot  { width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0; border: 2px solid #fff; box-shadow: 0 0 0 1.5px currentColor; }
    .g6-tooltip {
      background: #fff; border: 1px solid #e4e8f0; border-radius: 6px;
      box-shadow: 0 4px 16px rgba(0,0,0,0.12); padding: 10px 12px;
      max-width: 440px; pointer-events: none; font-size: 12px;
    }
  </style>
</head>
<body>
  <div id="header">
    <h1>__TITLE__ \u2014 Field Lineage Graph</h1>
    <span class="meta">__NODE_COUNT__ nodes &nbsp;&middot;&nbsp; __EDGE_COUNT__ edges &nbsp;&middot;&nbsp; AntV G6 v5</span>
  </div>
  <div id="container"></div>

  <div id="legend">
    <h4>Legend</h4>
    <div class="legend-item"><div class="legend-line" style="background:#1976d2"></div><span>READ</span></div>
    <div class="legend-item"><div class="legend-line" style="background:#f57c00"></div><span>WRITE</span></div>
    <div class="legend-item"><div class="legend-line" style="background:#9c27b0"></div><span>MIXED</span></div>
    <div class="legend-item"><div class="legend-line" style="background:repeating-linear-gradient(90deg,#388e3c 0,#388e3c 5px,transparent 5px,transparent 9px)"></div><span>INHERIT</span></div>
    <div style="margin-top:6px;border-top:1px solid #eee;padding-top:6px">
      <div class="legend-item"><div class="legend-dot" style="background:#5B8FF9;color:#5B8FF9"></div><span>Class</span></div>
      <div class="legend-item"><div class="legend-dot" style="background:#61DDAA;color:#61DDAA"></div><span>Parent class</span></div>
    </div>
  </div>

  <script>
    const NODES = __NODES_JSON__;
    const EDGES = __EDGES_JSON__;

    const EDGE_COLORS = {
      READ:    '#1976d2',
      WRITE:   '#f57c00',
      MIXED:   '#9c27b0',
      INHERIT: '#388e3c',
    };

    // Pick the highest-degree node as radial center; fall back to first node.
    function findCenterId(nodes, edges) {
      const deg = {};
      nodes.forEach(n => { deg[n.id] = 0; });
      edges.forEach(e => { deg[e.source] = (deg[e.source]||0)+1; deg[e.target] = (deg[e.target]||0)+1; });
      return nodes.reduce((best, n) => (deg[n.id]||0) >= (deg[best.id]||0) ? n : best, nodes[0]).id;
    }
    const focusNodeId = NODES.length ? findCenterId(NODES, EDGES) : undefined;

    (async () => {
      const graph = new G6.Graph({
        container: 'container',
        data: { nodes: NODES, edges: EDGES },
        layout: {
          type: 'radial',
          focusNode: focusNodeId,
          unitRadius: 160,
          linkDistance: 200,
          preventOverlap: true,
          nodeSize: 64,
          nodeSpacing: 24,
          strictRadial: false,
          maxPreventOverlapIteration: 300,
        },
        node: {
          style: (model) => {
            const isParent = model.data && model.data.nodeType === 'parent';
            return {
              size: 56,
              fill: isParent ? '#61DDAA' : '#5B8FF9',
              stroke: isParent ? '#2e8b57' : '#3068d6',
              lineWidth: 2,
              shadowBlur: 10,
              shadowColor: 'rgba(0,0,0,0.10)',
              cursor: 'pointer',
              labelText: model.id,
              labelFill: '#222',
              labelFontSize: 12,
              labelFontWeight: '600',
              labelOffsetY: 38,
              labelWordWrap: false,
              labelBackgroundFill: 'rgba(255,255,255,0.88)',
              labelBackgroundPadding: [2, 6, 2, 6],
              labelBackgroundRadius: 3,
            };
          },
        },
        edge: {
          style: (model) => {
            const type  = (model.data && model.data.edgeType) || 'READ';
            const color = EDGE_COLORS[type] || '#999';
            const isDashed = type === 'INHERIT';
            return {
              stroke: color,
              lineWidth: type === 'MIXED' ? 2.5 : 1.8,
              lineDash: isDashed ? [7, 4] : undefined,
              endArrow: true,
              endArrowFill: color,
              endArrowSize: 9,
              cursor: 'pointer',
              labelText: (model.data && model.data.shortLabel) || '',
              labelFontSize: 10,
              labelFill: color,
              labelBackgroundFill: 'rgba(255,255,255,0.88)',
              labelBackgroundPadding: [2, 5, 2, 5],
              labelBackgroundRadius: 3,
              labelOffsetY: -8,
            };
          },
        },
        behaviors: [
          'zoom-canvas',
          'drag-canvas',
          'drag-element',
          { type: 'click-select', multiple: false },
        ],
        plugins: [
          {
            type: 'tooltip',
            getContent: function(_evt, items) {
              if (!items || items.length === 0) return '';
              const item = items[0];
              const html = item.data && item.data.tooltipHtml;
              if (html) return '<div class="g6-tooltip">' + html + '</div>';
              return '<div class="g6-tooltip"><b>' + item.id + '</b></div>';
            },
          },
        ],
      });

      await graph.render();

      // Fit graph into viewport after radial layout is applied
      setTimeout(function() { graph.fitView({ padding: 60 }); }, 200);
    })();
  </script>
</body>
</html>
""";

        return template
                .replace("__TITLE__",       titleSafe)
                .replace("__NODE_COUNT__",  nodeCount)
                .replace("__EDGE_COUNT__",  edgeCount)
                .replace("__NODES_JSON__",  nodesJson)
                .replace("__EDGES_JSON__",  edgesJson);
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
}
