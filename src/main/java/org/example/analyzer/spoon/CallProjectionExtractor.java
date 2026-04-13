package org.example.analyzer.spoon;

import org.example.analyzer.spoon.implicit.GlobalMapRegistry;
import org.example.analyzer.spoon.implicit.GlobalMapRegistryBuilder;
import org.example.analyzer.spoon.implicit.ImplicitEqualityExtractor;
import org.example.analyzer.spoon.inter.InterProceduralExtractor;
import org.example.analyzer.spoon.intra.BuilderChainExtractor;
import org.example.analyzer.spoon.intra.ConstructorCallExtractor;
import org.example.analyzer.spoon.intra.DirectSetterExtractor;
import org.example.analyzer.spoon.structural.CompositionExtractor;
import org.example.model.FieldMapping;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtExecutable;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates all Spoon-based field-mapping patterns for a single method execution.
 *
 * {@link SpoonResolutionHelper} and {@link GlobalMapRegistry} are built once per analysis
 * batch by {@link SpoonAnalyzer} and injected here — no per-method rebuild.
 *
 * Thread-safety: one instance is created per method call; the injected helper and registry
 * are read-only shared objects safe for concurrent access.
 */
class CallProjectionExtractor {

    private final SpoonResolutionHelper helper;
    private final GlobalMapRegistry     registry;

    CallProjectionExtractor(SpoonResolutionHelper helper, GlobalMapRegistry registry) {
        this.helper   = helper;
        this.registry = registry;
    }

    void extract(CtExecutable<?> method, ExecutionContext ctx) {
        List<SpoonPatternExtractor> patterns = List.of(
                new CompositionExtractor(),
                new ConstructorCallExtractor(),
                new BuilderChainExtractor(),
                new DirectSetterExtractor(),
                new ImplicitEqualityExtractor(registry)
        );

        List<FieldMapping> results = new ArrayList<>();
        for (SpoonPatternExtractor p : patterns) {
            results.addAll(p.extract(method, ctx, helper));
        }

        results.addAll(new InterProceduralExtractor(helper).extract(method, ctx));

        this.results = results;
    }

    private List<FieldMapping> results = List.of();

    List<FieldMapping> results() {
        return results;
    }
}
