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
 * The {@link GlobalMapRegistry} is built lazily on the first {@code extract()} call
 * and cached for the lifetime of this instance — it is shared across all subsequent
 * per-method invocations within the same analysis batch.
 *
 * All pattern extractors share a single {@link SpoonResolutionHelper} instance per call.
 * The {@link ExecutionContext} replaces the flat aliasMap for scope-aware resolution.
 */
class CallProjectionExtractor {

    /** Built once per batch on first use; guarded by volatile for safe lazy init. */
    private volatile GlobalMapRegistry globalMapRegistry;

    void extract(CtExecutable<?> method, ExecutionContext ctx, CtModel model) {
        SpoonResolutionHelper helper = new SpoonResolutionHelper(model);

        List<SpoonPatternExtractor> patterns = List.of(
                new CompositionExtractor(),
                new ConstructorCallExtractor(),
                new BuilderChainExtractor(),
                new DirectSetterExtractor(),
                new ImplicitEqualityExtractor(registry(model))
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

    // ── lazy registry init ────────────────────────────────────────────────────

    /**
     * Double-checked lazy initialisation. Building the registry is idempotent,
     * so a race on first call produces the same result either way.
     */
    private GlobalMapRegistry registry(CtModel model) {
        if (globalMapRegistry == null) {
            synchronized (this) {
                if (globalMapRegistry == null) {
                    globalMapRegistry = new GlobalMapRegistryBuilder().build(model);
                }
            }
        }
        return globalMapRegistry;
    }
}
