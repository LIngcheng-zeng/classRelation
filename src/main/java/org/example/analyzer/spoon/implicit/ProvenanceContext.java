package org.example.analyzer.spoon.implicit;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.model.FieldProvenance;
import org.example.model.MapFact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Augments {@link ExecutionContext} with field provenance tracking.
 *
 * Uses composition (not inheritance) so it can wrap any ExecutionContext
 * without coupling to its internal layout.
 *
 * Two registries:
 *   varProvenance — maps local variable names to the field that produced their value
 *   mapFacts      — maps Map variable names to their key/value field provenance
 *
 * Child contexts (lambda / callee scope) are created via {@link #enterScope},
 * which produces a new ProvenanceContext that inherits the parent's registries
 * as read-only overlays, and accumulates new entries in its own layer.
 */
public final class ProvenanceContext {

    private final ExecutionContext              execCtx;
    private final ProvenanceContext             parent;
    private final Map<String, FieldProvenance>  varProvenance;
    private final Map<String, MapFact>          mapFacts;

    private ProvenanceContext(ExecutionContext execCtx,
                              ProvenanceContext parent,
                              Map<String, FieldProvenance> varProvenance,
                              Map<String, MapFact> mapFacts) {
        this.execCtx       = execCtx;
        this.parent        = parent;
        this.varProvenance = varProvenance;
        this.mapFacts      = mapFacts;
    }

    // ── factories ────────────────────────────────────────────────────────────

    public static ProvenanceContext forMethod(ExecutionContext execCtx) {
        return new ProvenanceContext(execCtx, null,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /**
     * Creates a child scope that inherits all parent facts and adds its own layer.
     * The parent's maps are not mutated; new entries go into the child's own maps.
     *
     * @param childExecCtx the ExecutionContext for the nested scope (lambda / callee)
     */
    public ProvenanceContext enterScope(ExecutionContext childExecCtx) {
        return new ProvenanceContext(childExecCtx, this,
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    // ── ExecutionContext delegation ───────────────────────────────────────────

    public ExecutionContext execCtx() { return execCtx; }

    // ── variable provenance ───────────────────────────────────────────────────

    public void registerVarProvenance(String varName, FieldProvenance provenance) {
        varProvenance.put(varName, provenance);
    }

    /**
     * Resolves the field provenance for a variable, walking up the scope chain.
     */
    public Optional<FieldProvenance> provenanceOf(String varName) {
        if (varProvenance.containsKey(varName))
            return Optional.of(varProvenance.get(varName));
        return parent != null ? parent.provenanceOf(varName) : Optional.empty();
    }

    public Map<String, FieldProvenance> varProvenances() {
        return Collections.unmodifiableMap(varProvenance);
    }

    // ── map facts ────────────────────────────────────────────────────────────

    public void registerMapFact(MapFact fact) {
        mapFacts.put(fact.variableName(), fact);
    }

    /**
     * Looks up a MapFact by variable name, walking up the scope chain.
     */
    public Optional<MapFact> mapFact(String varName) {
        if (mapFacts.containsKey(varName))
            return Optional.of(mapFacts.get(varName));
        return parent != null ? parent.mapFact(varName) : Optional.empty();
    }

    public Map<String, MapFact> mapFacts() {
        return Collections.unmodifiableMap(mapFacts);
    }
}
