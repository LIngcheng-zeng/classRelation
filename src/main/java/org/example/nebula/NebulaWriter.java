package org.example.nebula;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes ClassRelation data into NebulaGraph.
 *
 * Graph model (mirrors Mermaid output):
 *   Vertex TAG  : java_class       — one vertex per unique class FQN
 *   EDGE TYPE   : field_mapping    — one edge per FieldMapping (READ or WRITE, no TRANSITIVE)
 *   EDGE TYPE   : inherits         — one edge per InheritanceInfo (child → parent)
 */
public class NebulaWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NebulaWriter.class);

    private static final int BATCH_SIZE = 100;

    private final NebulaConfig config;
    private NebulaPool pool;

    public NebulaWriter(NebulaConfig config) {
        this.config = config;
    }

    /**
     * Full-overwrite write: drops existing space, recreates schema, inserts all data.
     */
    public void write(List<ClassRelation> relations) throws Exception {
        initPool();
        Session session = pool.getSession(config.username(), config.password(), false);
        try {
            new NebulaSchemaManager(session, config.spaceName()).initSchema();
            session.execute("USE `" + config.spaceName() + "`");

            insertVertices(session, relations);
            insertFieldMappingEdges(session, relations);
            insertInheritEdges(session, relations);

            log.info("NebulaGraph write complete. Space: {}", config.spaceName());
        } finally {
            session.release();
        }
    }

    // -------------------------------------------------------------------------
    // Vertex INSERT
    // -------------------------------------------------------------------------

    /**
     * Collects all unique class FQNs and inserts them as java_class vertices.
     */
    private void insertVertices(Session session, List<ClassRelation> relations) throws Exception {
        Set<String> fqns = new LinkedHashSet<>();
        for (ClassRelation rel : relations) {
            if (rel.sourceClass() != null) fqns.add(rel.sourceClass());
            if (rel.targetClass() != null) fqns.add(rel.targetClass());
            if (rel.inheritance() != null) {
                fqns.add(rel.inheritance().childClass());
                fqns.add(rel.inheritance().parentClass());
            }
        }

        List<String> fqnList = new ArrayList<>(fqns);
        for (int i = 0; i < fqnList.size(); i += BATCH_SIZE) {
            List<String> batch = fqnList.subList(i, Math.min(i + BATCH_SIZE, fqnList.size()));
            String nGQL = buildVertexInsert(batch);
            exec(session, nGQL);
        }
        log.info("Inserted {} java_class vertices.", fqnList.size());
    }

    private String buildVertexInsert(List<String> fqns) {
        StringBuilder sb = new StringBuilder("INSERT VERTEX java_class(fqn, simple_name) VALUES ");
        for (int i = 0; i < fqns.size(); i++) {
            String fqn        = fqns.get(i);
            String simpleName = extractSimpleName(fqn);
            sb.append('"').append(escape(fqn)).append('"')
              .append(":(\"").append(escape(fqn)).append("\", \"").append(escape(simpleName)).append("\")");
            if (i < fqns.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // field_mapping EDGE INSERT
    // -------------------------------------------------------------------------

    /**
     * Inserts one edge per FieldMapping (TRANSITIVE_CLOSURE excluded, same as Mermaid).
     * Deduplication key: (srcClass, tgtClass, leftSide, rightSide, mode) — mirrors MermaidRenderer.
     */
    private void insertFieldMappingEdges(Session session, List<ClassRelation> relations) throws Exception {
        // Collect rows with dedup
        record EdgeRow(String srcFqn, String tgtFqn, String srcField, String tgtField,
                       String mappingType, String mappingMode, String location, String rawExpr) {}

        Set<String>    dedupKeys = new LinkedHashSet<>();
        List<EdgeRow>  rows      = new ArrayList<>();

        for (ClassRelation rel : relations) {
            String srcFqn = rel.sourceClass();
            String tgtFqn = rel.targetClass();
            if (srcFqn == null || tgtFqn == null) continue;

            for (FieldMapping m : rel.mappings()) {
                if (m.mode() == MappingMode.TRANSITIVE_CLOSURE) continue;

                String dedupKey = srcFqn + "\0" + tgtFqn + "\0"
                        + sideToString(m.leftSide()) + "\0"
                        + sideToString(m.rightSide()) + "\0"
                        + m.mode();
                if (!dedupKeys.add(dedupKey)) continue;

                rows.add(new EdgeRow(
                    srcFqn, tgtFqn,
                    sideToString(m.leftSide()),
                    sideToString(m.rightSide()),
                    m.type().name(),
                    m.mode() == MappingMode.READ_PREDICATE ? "READ" : "WRITE",
                    m.location() != null ? m.location() : "",
                    m.rawExpression() != null ? m.rawExpression() : ""
                ));
            }
        }

        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<EdgeRow> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            StringBuilder sb = new StringBuilder(
                "INSERT EDGE field_mapping(src_field, tgt_field, mapping_type, mapping_mode, location, raw_expr) VALUES "
            );
            for (int j = 0; j < batch.size(); j++) {
                EdgeRow r = batch.get(j);
                sb.append('"').append(escape(r.srcFqn())).append("\"->\"").append(escape(r.tgtFqn())).append("\":")
                  .append("(\"").append(escape(r.srcField())).append("\", ")
                  .append("\"").append(escape(r.tgtField())).append("\", ")
                  .append("\"").append(escape(r.mappingType())).append("\", ")
                  .append("\"").append(escape(r.mappingMode())).append("\", ")
                  .append("\"").append(escape(r.location())).append("\", ")
                  .append("\"").append(escape(r.rawExpr())).append("\")");
                if (j < batch.size() - 1) sb.append(", ");
            }
            exec(session, sb.toString());
        }
        log.info("Inserted {} field_mapping edges.", rows.size());
    }

    // -------------------------------------------------------------------------
    // inherits EDGE INSERT
    // -------------------------------------------------------------------------

    private void insertInheritEdges(Session session, List<ClassRelation> relations) throws Exception {
        // Deduplicate inheritance pairs
        Map<String, ClassRelation.InheritanceInfo> seen = new LinkedHashMap<>();
        for (ClassRelation rel : relations) {
            if (rel.inheritance() == null) continue;
            ClassRelation.InheritanceInfo info = rel.inheritance();
            String key = info.childClass() + "->" + info.parentClass();
            seen.putIfAbsent(key, info);
        }

        List<ClassRelation.InheritanceInfo> infos = new ArrayList<>(seen.values());
        for (int i = 0; i < infos.size(); i += BATCH_SIZE) {
            List<ClassRelation.InheritanceInfo> batch = infos.subList(i, Math.min(i + BATCH_SIZE, infos.size()));
            StringBuilder sb = new StringBuilder("INSERT EDGE inherits(inherited_fields) VALUES ");
            for (int j = 0; j < batch.size(); j++) {
                ClassRelation.InheritanceInfo info = batch.get(j);
                String fields = info.inheritedFields() == null ? ""
                        : String.join(", ", info.inheritedFields());
                sb.append('"').append(escape(info.childClass())).append("\"->\"")
                  .append(escape(info.parentClass())).append("\":")
                  .append("(\"").append(escape(fields)).append("\")");
                if (j < batch.size() - 1) sb.append(", ");
            }
            exec(session, sb.toString());
        }
        log.info("Inserted {} inherits edges.", infos.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initPool() throws Exception {
        NebulaPoolConfig poolConfig = new NebulaPoolConfig();
        poolConfig.setMaxConnSize(1);
        pool = new NebulaPool();
        boolean ok = pool.init(
            List.of(new HostAddress(config.host(), config.port())),
            poolConfig
        );
        if (!ok) {
            throw new IllegalStateException("Failed to initialize NebulaPool at "
                + config.host() + ":" + config.port());
        }
    }

    private void exec(Session session, String nGQL) throws Exception {
        ResultSet rs = session.execute(nGQL);
        if (!rs.isSucceeded()) {
            throw new RuntimeException("nGQL failed [" + rs.getErrorMessage() + "]: "
                + nGQL.substring(0, Math.min(200, nGQL.length())));
        }
    }

    private String sideToString(org.example.model.ExpressionSide side) {
        if (side == null || side.isEmpty()) return "<unknown>";
        List<FieldRef> fields = side.fields();
        if (fields.size() == 1) {
            FieldRef f = fields.get(0);
            return extractSimpleName(f.className()) + "." + f.fieldName();
        }
        String joined = String.join(", ", fields.stream()
                .map(f -> extractSimpleName(f.className()) + "." + f.fieldName())
                .toList());
        return side.operatorDesc() + "(" + joined + ")";
    }

    private String extractSimpleName(String fqn) {
        if (fqn == null) return "?";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /** Escapes backslash and double-quote for nGQL string literals. */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
