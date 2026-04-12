package org.example.analyzer.spoon.implicit;

/**
 * Phase 1 SPI: scans a pre-collected {@link MethodScanResult} and populates
 * the {@link ProvenanceContext} with {@link org.example.model.MapFact}s
 * and variable provenance entries.
 *
 * Implementations are stateless. All mutable state goes into {@code ctx}.
 *
 * Replaces the {@code collectMapFacts(CtExecutable, ProvenanceContext)} half
 * of the old {@link KeyUsagePattern} interface, and eliminates the need for
 * Phase 2-only classes to implement an empty stub.
 */
public interface MapFactCollector {

    /**
     * Inspect {@code scan} and register any discovered MapFacts or variable
     * provenances into {@code ctx}.
     *
     * @param scan pre-collected AST nodes for the method being analyzed
     * @param ctx  mutable provenance context; call {@link ProvenanceContext#registerMapFact}
     *             and {@link ProvenanceContext#registerVarProvenance} as appropriate
     */
    void collectMapFacts(MethodScanResult scan, ProvenanceContext ctx);
}
