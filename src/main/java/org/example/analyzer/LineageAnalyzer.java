package org.example.analyzer;

import org.example.analyzer.javaparser.JavaParserAnalyzer;
import org.example.analyzer.spoon.SpoonAnalyzer;
import org.example.expander.TransitiveClosureExpander;
import org.example.graph.LineageGraph;
import org.example.model.ClassRelation;
import org.example.model.FieldMapping;
import org.example.resolution.FieldRefQualifier;
import org.example.resolution.SymbolResolutionResult;
import org.example.resolution.SymbolResolver;
import org.example.spi.SourceAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Orchestrates the full lineage analysis pipeline.
 *
 * Explicit five-phase pipeline (no shared mutable state):
 *
 *   Phase 0 — Symbol Resolution
 *             {@link SymbolResolver} builds CtModel, FieldTypeMap, inheritanceIndex,
 *             and classPackageIndex in one place before any extractor runs.
 *
 *   Phase 1 — Extraction
 *             Each {@link SourceAnalyzer} receives the immutable {@link SymbolResolutionResult}
 *             and returns raw {@link FieldMapping}s. Analyzers are independent of each other.
 *
 *   Phase 2 — Qualification
 *             {@link FieldRefQualifier} normalises all FieldRef class names to FQN in a
 *             single pass over the combined output of all extractors.
 *
 *   Phase 3 — Aggregation
 *             {@link LineageGraph} groups qualified mappings into {@link ClassRelation}s
 *             and attaches inheritance metadata.
 *
 *   Phase 4 — Enrichment
 *             {@link TransitiveClosureExpander} derives indirect relations.
 */
public class LineageAnalyzer {

    private final List<SourceAnalyzer>      analyzers;
    private final SymbolResolver            symbolResolver = new SymbolResolver();
    private final TransitiveClosureExpander expander       = new TransitiveClosureExpander();

    /** Default: JavaParser for direct assignments/setters/equals; Spoon for inter-procedural chains. */
    public LineageAnalyzer() {
        this(List.of(new JavaParserAnalyzer(), new SpoonAnalyzer()));
    }

    public LineageAnalyzer(List<SourceAnalyzer> analyzers) {
        this.analyzers = List.copyOf(analyzers);
    }

    public List<ClassRelation> analyze(Path projectRoot) {
        // Phase 0: Symbol Resolution
        SymbolResolutionResult symbols = symbolResolver.resolve(projectRoot);

        // Phase 1: Extraction (all analyzers are independent)
        List<FieldMapping> raw = new ArrayList<>();
        for (SourceAnalyzer analyzer : analyzers) {
            raw.addAll(analyzer.analyze(projectRoot, symbols));
        }

        // Phase 2: Qualification (single pass, applies to all extractor output)
        List<FieldMapping> qualified = new FieldRefQualifier(symbols).qualify(raw);

        // Phase 2.5: User-class filter — drop any mapping where either side
        // lacks at least one class that exists in the analyzed project.
        // Uses classPackageIndex (simpleName → FQN) as the authoritative set of user classes.
        Set<String> userClassFqns = new HashSet<>(symbols.classPackageIndex().values());
        List<FieldMapping> userOnly = qualified.stream()
                .filter(m -> bothSidesHaveUserClass(m, userClassFqns))
                .collect(Collectors.toList());

        // Phase 3: Aggregation
        LineageGraph graph = new LineageGraph();
        userOnly.forEach(graph::addMapping);
        graph.setInheritanceMap(symbols.inheritanceIndex());

        // Phase 4: Enrichment
        return expander.expand(graph.buildRelations());
    }

    // -------------------------------------------------------------------------
    // Phase 2.5 helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if both sides have at least one class that exists in the
     * analyzed project (i.e., appears in classPackageIndex).
     *
     * This is the authoritative definition of "user-defined class":
     * any class whose source was scanned — no hardcoded package prefix lists needed.
     */
    private static boolean bothSidesHaveUserClass(FieldMapping m, Set<String> userClassFqns) {
        boolean sourceHasUser = m.leftSide().fields().stream()
                .anyMatch(f -> userClassFqns.contains(f.className()));
        boolean sinkHasUser = m.rightSide().fields().stream()
                .anyMatch(f -> userClassFqns.contains(f.className()));
        return sourceHasUser && sinkHasUser;
    }
}
