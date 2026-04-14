package org.example.nebula;

import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles NebulaGraph DDL: DROP/CREATE SPACE, TAG, EDGE TYPE.
 * Full-overwrite strategy: drop existing space and rebuild from scratch.
 */
public class NebulaSchemaManager {

    private static final Logger log = LoggerFactory.getLogger(NebulaSchemaManager.class);

    private static final int SPACE_WAIT_INTERVAL_MS = 1000;
    private static final int SPACE_WAIT_MAX_ATTEMPTS = 30;

    private final Session session;
    private final String  spaceName;

    public NebulaSchemaManager(Session session, String spaceName) {
        this.session   = session;
        this.spaceName = spaceName;
    }

    /**
     * Drops and recreates the space, then creates all TAGs and EDGE TYPEs.
     * Blocks until the space and schema are ready for DML.
     */
    public void initSchema() throws Exception {
        dropAndCreateSpace();
        waitForSpaceReady();
        createTagsAndEdges();
        waitForSchemaSettle();
    }

    // -------------------------------------------------------------------------
    // Space lifecycle
    // -------------------------------------------------------------------------

    private void dropAndCreateSpace() throws Exception {
        String quoted = quote(spaceName);

        exec("DROP SPACE IF EXISTS " + quoted);
        log.info("Dropped space (if existed): {}", spaceName);

        // DROP SPACE is asynchronous — wait until it fully disappears from the meta
        waitForSpaceAbsent();

        String createDdl = String.format(
            "CREATE SPACE %s (partition_num = 1, replica_factor = 1, vid_type = FIXED_STRING(256))",
            quoted
        );
        exec(createDdl);
        log.info("Created space: {}", spaceName);
    }

    /**
     * Polls SHOW SPACES until the target space is gone (DROP finished on all meta nodes).
     */
    private void waitForSpaceAbsent() throws Exception {
        log.info("Waiting for space '{}' to be fully dropped...", spaceName);
        for (int attempt = 1; attempt <= SPACE_WAIT_MAX_ATTEMPTS; attempt++) {
            ResultSet rs = session.execute("SHOW SPACES");
            if (rs.isSucceeded()) {
                boolean stillPresent = false;
                for (int row = 0; row < rs.rowsSize(); row++) {
                    if (spaceName.equalsIgnoreCase(rs.rowValues(row).get(0).asString())) {
                        stillPresent = true;
                        break;
                    }
                }
                if (!stillPresent) {
                    log.info("Space '{}' fully dropped after {} attempt(s).", spaceName, attempt);
                    return;
                }
            }
            Thread.sleep(SPACE_WAIT_INTERVAL_MS);
        }
        throw new IllegalStateException("Timed out waiting for space '" + spaceName + "' to be dropped");
    }

    /**
     * Polls until the space is both visible in SHOW SPACES and usable via USE.
     * NebulaGraph's StorageD may finish assigning partitions a few seconds after
     * the space first appears in SHOW SPACES.
     */
    private void waitForSpaceReady() throws Exception {
        log.info("Waiting for space '{}' to become ready...", spaceName);
        for (int attempt = 1; attempt <= SPACE_WAIT_MAX_ATTEMPTS; attempt++) {
            // First check that SHOW SPACES lists it
            boolean visible = false;
            ResultSet showRs = session.execute("SHOW SPACES");
            if (showRs.isSucceeded()) {
                for (int row = 0; row < showRs.rowsSize(); row++) {
                    if (spaceName.equalsIgnoreCase(showRs.rowValues(row).get(0).asString())) {
                        visible = true;
                        break;
                    }
                }
            }

            if (visible) {
                // Then verify USE succeeds (StorageD fully initialized)
                ResultSet useRs = session.execute("USE `" + spaceName + "`");
                if (useRs.isSucceeded()) {
                    log.info("Space '{}' ready and usable after {} attempt(s).", spaceName, attempt);
                    return;
                }
            }

            log.debug("Space not ready yet (attempt {}), retrying in {}ms...", attempt, SPACE_WAIT_INTERVAL_MS);
            Thread.sleep(SPACE_WAIT_INTERVAL_MS);
        }
        throw new IllegalStateException("Timed out waiting for space '" + spaceName + "' to become ready");
    }

    // -------------------------------------------------------------------------
    // TAG and EDGE TYPE definitions
    // -------------------------------------------------------------------------

    private void createTagsAndEdges() throws Exception {
        // Space is already selected by waitForSpaceReady()
        exec("CREATE TAG IF NOT EXISTS java_class(fqn string, simple_name string)");
        log.info("TAG java_class created.");

        exec("""
            CREATE EDGE IF NOT EXISTS field_mapping(
                src_field    string,
                tgt_field    string,
                mapping_type string,
                mapping_mode string,
                location     string,
                raw_expr     string
            )""");
        log.info("EDGE field_mapping created.");

        exec("CREATE EDGE IF NOT EXISTS inherits(inherited_fields string)");
        log.info("EDGE inherits created.");
    }

    /**
     * Two-phase schema settle:
     *   Phase 1 — poll SHOW TAGS until 'java_class' is visible in the meta layer.
     *             This eliminates "No schema found" SemanticErrors in phase 2.
     *   Phase 2 — probe with a real INSERT to confirm StorageD has synced the schema.
     *             Only a successful DML guarantees full readiness.
     */
    private void waitForSchemaSettle() throws Exception {
        waitForTagVisible();
        waitForStorageDReady();
    }

    /** Phase 1: wait until SHOW TAGS lists 'java_class'. */
    private void waitForTagVisible() throws Exception {
        log.info("Waiting for TAG 'java_class' to be visible in meta...");
        for (int attempt = 1; attempt <= SPACE_WAIT_MAX_ATTEMPTS; attempt++) {
            ResultSet rs = session.execute("SHOW TAGS");
            if (rs.isSucceeded()) {
                for (int row = 0; row < rs.rowsSize(); row++) {
                    if ("java_class".equalsIgnoreCase(rs.rowValues(row).get(0).asString())) {
                        log.info("TAG 'java_class' visible after {} attempt(s).", attempt);
                        return;
                    }
                }
            }
            log.debug("TAG not visible yet (attempt {}), retrying...", attempt);
            Thread.sleep(SPACE_WAIT_INTERVAL_MS);
        }
        throw new IllegalStateException("Timed out waiting for TAG 'java_class' to appear in meta");
    }

    /** Phase 2: probe with a real INSERT to confirm StorageD DML readiness. */
    private void waitForStorageDReady() throws Exception {
        log.info("Probing StorageD readiness via test INSERT...");
        String probeVid   = "__schema_probe__";
        String probeNgql  = "INSERT VERTEX java_class(fqn, simple_name) VALUES "
                          + "\"" + probeVid + "\":(\"probe\", \"probe\")";
        String deleteNgql = "DELETE VERTEX \"" + probeVid + "\"";

        for (int attempt = 1; attempt <= SPACE_WAIT_MAX_ATTEMPTS; attempt++) {
            ResultSet rs = session.execute(probeNgql);
            if (rs.isSucceeded()) {
                session.execute(deleteNgql);   // cleanup probe vertex
                log.info("StorageD ready after {} probe attempt(s).", attempt);
                return;
            }
            log.debug("StorageD not ready yet (attempt {}): {}", attempt, rs.getErrorMessage());
            Thread.sleep(SPACE_WAIT_INTERVAL_MS);
        }
        throw new IllegalStateException("Timed out waiting for StorageD to accept DML");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void exec(String nGQL) throws Exception {
        ResultSet rs = session.execute(nGQL);
        if (!rs.isSucceeded()) {
            throw new RuntimeException("nGQL failed [" + rs.getErrorMessage() + "]: " + nGQL);
        }
    }

    /** Wraps identifier in backticks for NebulaGraph. */
    private static String quote(String name) {
        return "`" + name + "`";
    }
}
