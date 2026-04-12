package org.example.analyzer.spoon.implicit;

import org.example.model.FieldMapping;
import org.example.model.MapFact;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;

import java.util.List;
import java.util.Optional;

/**
 * SPI for implicit equality detection patterns.
 *
 * Implementations cover one specific syntactic pattern in which field values
 * are used as implicit join keys (e.g. Collectors.toMap, Map.forEach, groupingBy).
 *
 * Two-phase protocol:
 *
 *   Phase 1 — collectMapFacts:
 *     Scan the method body to build a ProvenanceContext populated with MapFacts.
 *     Each MapFact records which source fields produced the key and value of a Map variable.
 *
 *   Phase 2 — detectBridges:
 *     Scan the method body for sites where a value with known provenance is used
 *     as a lookup key into a Map with a *different* key provenance.
 *     Such a mismatch reveals an implicit equality: callerKeyField ≡ mapKeyField.
 *
 * Implementations must be stateless — both methods receive all necessary state
 * via ProvenanceContext and return results without side effects.
 */
public interface KeyUsagePattern {

    /**
     * Phase 1: extract MapFacts from expressions inside {@code method}.
     *
     * @param method the executable being analyzed
     * @param ctx    mutable provenance context; implementations should call
     *               {@link ProvenanceContext#registerMapFact} for each fact found
     */
    void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx);

    /**
     * Phase 2: detect implicit equalities by finding bridge sites where a value
     * with known provenance is used as a key into a Map whose key provenance differs.
     *
     * @param method the executable being analyzed
     * @param ctx    provenance context populated by Phase 1
     * @return zero or more FieldMappings with {@code MappingType.MAP_JOIN}
     */
    List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx);
}
