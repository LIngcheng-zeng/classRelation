package org.example.analyzer;

import org.example.analyzer.javaparser.JavaParserAnalyzer;
import org.example.expander.TransitiveClosureExpander;
import org.example.graph.LineageGraph;
import org.example.model.ClassRelation;
import org.example.spi.SourceAnalyzer;

import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full lineage analysis pipeline.
 *
 * Responsibilities:
 *   1. Delegate source extraction to each registered {@link SourceAnalyzer}
 *   2. Aggregate all {@link org.example.model.FieldMapping}s into a {@link LineageGraph}
 *   3. Expand transitive relations via {@link TransitiveClosureExpander}
 *
 * Adding a new backend (e.g. Spoon):
 *   new LineageAnalyzer(List.of(new JavaParserAnalyzer(), new SpoonAnalyzer()))
 */
public class LineageAnalyzer {

    private final List<SourceAnalyzer>      analyzers;
    private final TransitiveClosureExpander expander = new TransitiveClosureExpander();

    /** Default constructor: uses only the JavaParser backend. */
    public LineageAnalyzer() {
        this(List.of(new JavaParserAnalyzer()));
    }

    public LineageAnalyzer(List<SourceAnalyzer> analyzers) {
        this.analyzers = List.copyOf(analyzers);
    }

    public List<ClassRelation> analyze(Path projectRoot) {
        LineageGraph graph = new LineageGraph();
        for (SourceAnalyzer analyzer : analyzers) {
            analyzer.analyze(projectRoot).forEach(graph::addMapping);
        }
        return expander.expand(graph.buildRelations());
    }
}
