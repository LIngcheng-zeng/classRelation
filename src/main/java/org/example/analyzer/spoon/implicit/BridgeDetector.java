package org.example.analyzer.spoon.implicit;

import org.example.model.FieldMapping;

import java.util.List;

/**
 * Phase 2 SPI: inspects a fully-populated {@link ProvenanceContext} to find
 * implicit field equalities and emit {@link FieldMapping}s.
 *
 * Receives a {@link CrossFileMapResolver} so it can resolve Map variables
 * whose facts live outside the current method (fields, method returns, statics).
 *
 * Implementations are stateless. Results are returned without side effects.
 *
 * Replaces the {@code detectBridges(CtExecutable, ProvenanceContext)} half
 * of the old {@link KeyUsagePattern} interface, and eliminates the need for
 * Phase 1-only classes to implement an empty stub.
 */
public interface BridgeDetector {

    /**
     * Detect implicit equalities from the fully-populated provenance context.
     *
     * @param scan     pre-collected AST nodes for the method being analyzed
     * @param ctx      provenance context populated by all Phase 1 collectors
     * @param resolver cross-file Map resolver for targets not in the local context
     * @return zero or more FieldMappings with {@code MappingType.MAP_JOIN}
     */
    List<FieldMapping> detectBridges(MethodScanResult scan,
                                     ProvenanceContext ctx,
                                     CrossFileMapResolver resolver);
}
