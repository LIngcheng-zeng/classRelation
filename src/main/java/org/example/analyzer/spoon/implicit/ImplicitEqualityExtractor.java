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
 * Implements {@link SpoonPatternExtractor} so it plugs directly into
 * {@link org.example.analyzer.spoon.CallProjectionExtractor} alongside
 * existing pattern extractors.
 *
 * Phase 1 — collectMapFacts:
 *   Each registered {@link KeyUsagePattern} scans the method body and populates
 *   the {@link ProvenanceContext} with {@link org.example.model.MapFact}s.
 *   All patterns run before any bridge detection, so later patterns can see
 *   facts produced by earlier ones.
 *
 * Phase 2 — detectBridges:
 *   Each registered pattern inspects the fully-populated ProvenanceContext to
 *   find implicit equalities and emit {@link FieldMapping}s.
 *
 * To add a new implicit-equality pattern:
 *   1. Implement {@link KeyUsagePattern}.
 *   2. Add an instance to the {@code patterns} list in this class.
 *   No other change required.
 */
public class ImplicitEqualityExtractor implements SpoonPatternExtractor {

    /**
     * Registered patterns in execution order.
     *
     * Phase 1 (collectMapFacts) runs all patterns before Phase 2, so every
     * MapFact and variable provenance is available to all bridge detectors.
     *
     * Phase 1 patterns (MapFact / provenance collectors):
     *   - CollectorsToMapPattern       stream.collect(Collectors.toMap(A::getX, A::getY))
     *   - GroupingByPattern            stream.collect(Collectors.groupingBy(A::getX))
     *   - ExplicitMapPutPattern        map.put(a.getX(), b.getY())
     *   - GetterAssignmentProvenancePattern  String x = obj.getField()
     *
     * Phase 2 patterns (bridge / equality detectors):
     *   - ForEachGetBridgePattern      map1.forEach((k,v) -> map2.get(k))
     *   - DirectGetBridgePattern       map.get(obj.getField()) | map.get(var)
     *   - StreamFilterBridgePattern    filter(x -> x.getF().equals(y))
     *
     * To add a new pattern: implement KeyUsagePattern and add an instance here.
     */
    private final List<KeyUsagePattern> patterns = List.of(
            // Phase 1 — MapFact / provenance collectors
            new CollectorsToMapPattern(),
            new GroupingByPattern(),
            new ExplicitMapPutPattern(),
            new GetterAssignmentProvenancePattern(),
            // Phase 2 — bridge / equality detectors
            new ForEachGetBridgePattern(),
            new DirectGetBridgePattern(),
            new StreamFilterBridgePattern()
    );

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      ExecutionContext ctx,
                                      SpoonResolutionHelper helper) {
        ProvenanceContext provCtx = ProvenanceContext.forMethod(ctx);

        // Phase 1: all patterns collect Map facts first
        for (KeyUsagePattern pattern : patterns) {
            pattern.collectMapFacts(method, provCtx);
        }

        // Phase 2: all patterns detect bridges against the fully-populated context
        List<FieldMapping> results = new ArrayList<>();
        for (KeyUsagePattern pattern : patterns) {
            results.addAll(pattern.detectBridges(method, provCtx));
        }

        return results;
    }
}
