package org.example.analyzer.spoon.implicit;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.analyzer.spoon.SpoonPatternExtractor;
import org.example.analyzer.spoon.SpoonResolutionHelper;
import org.example.model.FieldMapping;
import spoon.reflect.declaration.CtExecutable;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the two-phase implicit equality detection pipeline.
 *
 * Changes from the original design:
 *
 *   1. Single-pass AST scan — {@link MethodScanResult#of} collects all relevant
 *      node types in one {@link spoon.reflect.visitor.CtScanner} traversal;
 *      all patterns receive the pre-collected lists instead of calling
 *      {@code getElements()} independently.
 *
 *   2. Interface split — {@link MapFactCollector} for Phase 1,
 *      {@link BridgeDetector} for Phase 2. No more empty stub implementations.
 *
 *   3. Cross-file resolution — a {@link GlobalMapRegistry} (built once per batch
 *      by the caller) is passed to each {@link BridgeDetector} via a
 *      {@link CrossFileMapResolver}, enabling Map facts defined in other classes
 *      (fields, method returns, statics) to be resolved at bridge-detection time.
 *
 * To add a new pattern:
 *   - Implement {@link MapFactCollector} and/or {@link BridgeDetector}.
 *   - Add an instance to {@code collectors} or {@code detectors} below.
 *   No other change required.
 */
public class ImplicitEqualityExtractor implements SpoonPatternExtractor {

    private final GlobalMapRegistry globalRegistry;

    /** Phase 1 — MapFact / provenance collectors, in execution order. */
    private final List<MapFactCollector> collectors = List.of(
            new CollectorsToMapPattern(),
            new GroupingByPattern(),
            new ExplicitMapPutPattern(),
            new GetterAssignmentProvenancePattern()
    );

    /** Phase 2 — bridge / equality detectors, in execution order. */
    private final List<BridgeDetector> detectors = List.of(
            new ForEachGetBridgePattern(),
            new DirectGetBridgePattern(),
            new StreamFilterBridgePattern()
    );

    public ImplicitEqualityExtractor(GlobalMapRegistry globalRegistry) {
        this.globalRegistry = globalRegistry;
    }

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      ExecutionContext ctx,
                                      SpoonResolutionHelper helper) {
        // Single traversal: collect all AST nodes used by any pattern
        MethodScanResult scan = MethodScanResult.of(method);

        ProvenanceContext provCtx = ProvenanceContext.forMethod(ctx);
        CrossFileMapResolver resolver = new CrossFileMapResolver(globalRegistry);

        // Phase 1: all collectors populate provCtx before any bridge detection
        for (MapFactCollector collector : collectors) {
            collector.collectMapFacts(scan, provCtx);
        }

        // Phase 2: all detectors inspect the fully-populated provCtx
        List<FieldMapping> results = new ArrayList<>();
        for (BridgeDetector detector : detectors) {
            results.addAll(detector.detectBridges(scan, provCtx, resolver));
        }

        return results;
    }
}
