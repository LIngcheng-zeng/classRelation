package org.example.analyzer.spoon;

import org.example.model.FieldMapping;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtExecutable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates all Spoon-based field-mapping patterns for a single method execution.
 *
 * Delegates each pattern to a focused {@link SpoonPatternExtractor} implementation:
 *   1. {@link CompositionExtractor}     — getter return-type aggregation relationships
 *   2. {@link ConstructorCallExtractor} — constructor argument → parameter mappings
 *   3. {@link BuilderChainExtractor}    — builder().field(x).build() chains
 *   4. {@link DirectSetterExtractor}    — intra-procedural obj.setXxx(expr) calls
 *   5. {@link InterProceduralExtractor} — cross-method projection (depth-limited, cycle-guarded)
 *
 * All pattern extractors share a single {@link SpoonResolutionHelper} instance
 * which encapsulates the CtModel and common resolution utilities.
 */
class CallProjectionExtractor {

    void extract(CtExecutable<?> method, Map<String, CtExpression<?>> aliasMap, CtModel model) {
        SpoonResolutionHelper helper = new SpoonResolutionHelper(model);

        List<SpoonPatternExtractor> patterns = List.of(
                new CompositionExtractor(),
                new ConstructorCallExtractor(),
                new BuilderChainExtractor(),
                new DirectSetterExtractor()
        );

        List<FieldMapping> results = new ArrayList<>();
        for (SpoonPatternExtractor p : patterns) {
            results.addAll(p.extract(method, aliasMap, helper));
        }

        results.addAll(new InterProceduralExtractor(helper).extract(method, aliasMap));

        this.results = results;
    }

    private List<FieldMapping> results = List.of();

    List<FieldMapping> results() {
        return results;
    }
}
