package org.example.analyzer.spoon.implicit;

import org.example.model.MapFact;

/**
 * Phase 1 SPI: scans a pre-collected {@link MethodScanResult} and populates
 * the {@link ProvenanceContext} with {@link MapFact}s
 * and variable provenance entries.
 *
 * Implementations are stateless. All mutable state goes into {@code ctx}.
 *
 * For cross-file scenarios, implementations should also register MapFacts to
 * the {@link GlobalMapRegistry} (via {@code globalRegistry.register(...)}) so
 * that other classes can resolve them during bridge detection.
 *
 * Replaces the {@code collectMapFacts(CtExecutable, ProvenanceContext)} half
 * of the old {@link KeyUsagePattern} interface, and eliminates the need for
 * Phase 2-only classes to implement an empty stub.
 */
public interface MapFactCollector {

    /**
     * Inspect {@code scan} and register any discovered MapFacts or variable
     * provenances into {@code ctx}. Optionally register to {@code globalRegistry}
     * for cross-file resolution.
     *
     * @param scan pre-collected AST nodes for the method being analyzed
     * @param ctx  mutable provenance context; call {@link ProvenanceContext#registerMapFact}
     *             and {@link ProvenanceContext#registerVarProvenance} as appropriate
     * @param globalRegistry global registry for cross-file Map fact sharing (may be null)
     */
    void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx, GlobalMapRegistry globalRegistry);
}
