package org.example.analyzer.spoon.implicit;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.model.FieldProvenance;
import org.example.model.MapFact;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Optional;

/**
 * Builds a {@link GlobalMapRegistry} by scanning all types in the {@link CtModel}.
 *
 * Run once per analysis batch before any per-method extraction.
 * The cost is proportional to the number of types in the model, not the number
 * of method invocations — so it amortises to near-zero per method call.
 *
 * Three scenarios are covered:
 *
 *   Scenario 1 — Instance field of Map type:
 *     Detected when a field is directly initialised, or when a method assigns
 *     a locally-built Map to a field via {@code this.fieldName = ...}.
 *     Also registers a method-return entry for any getter that returns the field.
 *
 *   Scenario 2 — Method returning Map:
 *     Detected when a method's return type is a Map subtype and its body
 *     contains a return statement whose expression resolves to a MapFact,
 *     or when the method returns a field for which a fact already exists.
 *
 *   Scenario 3 — Static field of Map type:
 *     Same as Scenario 1 but for static fields (including static initialisers).
 */
public final class GlobalMapRegistryBuilder {

    /** Phase 1 collectors reused to extract MapFacts from method scan results. */
    private final List<MapFactCollector> collectors = List.of(
            new CollectorsToMapPattern(),
            new GroupingByPattern(),
            new ExplicitMapPutPattern(),
            new GetterAssignmentProvenancePattern()
    );

    public GlobalMapRegistry build(CtModel model) {
        GlobalMapRegistry registry = new GlobalMapRegistry();

        for (CtType<?> type : model.getAllTypes()) {
            String className = type.getSimpleName();
            // Pass 1: field initialisers + assignments to Map fields in method bodies
            scanFields(type, className, registry);
            scanMethodsForFieldFacts(type, className, registry);
            // Pass 2: methods returning Map — may reference field facts from pass 1
            scanMethodsForReturnFacts(type, className, registry);
        }

        return registry;
    }

    // ── field scanning ────────────────────────────────────────────────────────

    private void scanFields(CtType<?> type, String className, GlobalMapRegistry registry) {
        for (CtField<?> field : type.getFields()) {
            if (!isMapType(field.getType())) continue;

            String fieldName = field.getSimpleName();
            boolean isStatic = field.isStatic();

            // Direct field initializer: Map<K,V> field = stream.collect(...)
            if (field.getDefaultExpression() != null) {
                extractFromExpression(fieldName, field.getDefaultExpression())
                        .ifPresent(fact -> {
                            if (isStatic) registry.registerStaticFact(className, fieldName, fact);
                            else          registry.registerFieldFact(className, fieldName, fact);
                        });
            }
        }
    }

    // ── method scanning — pass 1 (field facts) ───────────────────────────────

    private void scanMethodsForFieldFacts(CtType<?> type, String className, GlobalMapRegistry registry) {
        for (CtMethod<?> method : type.getMethods()) {
            try {
                MethodScanResult scan    = MethodScanResult.of(method);
                ExecutionContext  execCtx = ExecutionContext.forMethod(method);
                ProvenanceContext provCtx = ProvenanceContext.forMethod(execCtx);

                collectors.forEach(c -> c.collectMapFacts(scan, provCtx,registry));
                detectFieldAssignments(scan, provCtx, type, className, registry);
            } catch (Exception ignored) {}
        }
    }

    // ── method scanning — pass 2 (return facts, depends on pass 1) ───────────

    private void scanMethodsForReturnFacts(CtType<?> type, String className, GlobalMapRegistry registry) {
        for (CtMethod<?> method : type.getMethods()) {
            try {
                if (!isMapType(method.getType())) continue;

                MethodScanResult scan    = MethodScanResult.of(method);
                ExecutionContext  execCtx = ExecutionContext.forMethod(method);
                ProvenanceContext provCtx = ProvenanceContext.forMethod(execCtx);

                collectors.forEach(c -> c.collectMapFacts(scan, provCtx,registry));
                detectReturnFact(scan, provCtx, method, className, type, registry);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Scans assignment statements for patterns like:
     *   this.fieldName = localMapVar;
     *   this.fieldName = stream.collect(...);
     *   staticField    = ...;
     */
    private void detectFieldAssignments(MethodScanResult scan,
                                        ProvenanceContext provCtx,
                                        CtType<?> type,
                                        String className,
                                        GlobalMapRegistry registry) {
        for (var assign : scan.assignments) {
            try {
                if (!(assign.getAssigned() instanceof CtFieldWrite<?> fw)) continue;

                CtTypeReference<?> fieldType = fw.getType();
                if (fieldType == null || !isMapType(fieldType)) continue;

                String fieldName = fw.getVariable().getSimpleName();
                boolean isStatic = isStaticField(fw, type);

                // Try to get MapFact for the right-hand side
                Optional<MapFact> factOpt = resolveRhs(assign.getAssignment(), fieldName, provCtx);
                factOpt.ifPresent(fact -> {
                    MapFact namedFact = MapFact.of(fieldName,
                            fact.keyProvenance(), fact.valueProvenance(), fact.sourceExpression());
                    if (isStatic) registry.registerStaticFact(className, fieldName, namedFact);
                    else          registry.registerFieldFact(className, fieldName, namedFact);
                });
            } catch (Exception ignored) {}
        }
    }

    /**
     * For methods that return a Map, tries to derive a MapFact from:
     *   1. A return statement returning a local variable with known provenance.
     *   2. A return statement returning a field read whose fact is already registered.
     *   3. A getter body: return this.fieldName — delegates to field facts.
     */
    private void detectReturnFact(MethodScanResult scan,
                                  ProvenanceContext provCtx,
                                  CtMethod<?> method,
                                  String className,
                                  CtType<?> type,
                                  GlobalMapRegistry registry) {
        try {
            String methodName = method.getSimpleName();

            for (CtReturn<?> ret : method.getElements(new TypeFilter<>(CtReturn.class))) {
                CtExpression<?> returnedExpr = ret.getReturnedExpression();
                if (returnedExpr == null) continue;

                // Case 1: return localVar — check provCtx for its MapFact
                if (returnedExpr instanceof CtVariableRead<?> vr) {
                    String varName = vr.getVariable().getSimpleName();
                    provCtx.mapFact(varName).ifPresent(fact -> {
                        MapFact returnFact = MapFact.of(methodName + "()",
                                fact.keyProvenance(), fact.valueProvenance(), fact.sourceExpression());
                        registry.registerReturnFact(className, methodName, returnFact);
                    });
                }

                // Case 2: return this.fieldName — look up the field fact we already know
                if (returnedExpr instanceof CtFieldRead<?> fr) {
                    String fieldName = fr.getVariable().getSimpleName();
                    boolean isStatic = isStaticFieldRef(fr, type);
                    Optional<MapFact> fieldFact = isStatic
                            ? registry.lookupStatic(className, fieldName)
                            : registry.lookupField(className, fieldName);
                    fieldFact.ifPresent(fact -> {
                        MapFact returnFact = MapFact.of(methodName + "()",
                                fact.keyProvenance(), fact.valueProvenance(), fact.sourceExpression());
                        registry.registerReturnFact(className, methodName, returnFact);
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Tries to derive a MapFact from a right-hand side expression.
     * Checks: (a) local provenance context, (b) direct expression extraction.
     */
    private Optional<MapFact> resolveRhs(CtExpression<?> rhs,
                                          String fallbackVarName,
                                          ProvenanceContext provCtx) {
        if (rhs == null) return Optional.empty();

        // Local variable whose MapFact was already registered by Phase 1 collectors
        if (rhs instanceof CtVariableRead<?> vr) {
            return provCtx.mapFact(vr.getVariable().getSimpleName());
        }

        // Inline expression (e.g. stream.collect(...))
        return extractFromExpression(fallbackVarName, rhs);
    }

    /**
     * Attempts to extract a MapFact directly from an initializer-style expression
     * (e.g. {@code stream.collect(Collectors.toMap(A::getX, A::getY))}).
     *
     * Delegates to a temporary single-variable ProvenanceContext so the existing
     * CollectorsToMapPattern / GroupingByPattern logic can be reused without change.
     */
    private Optional<MapFact> extractFromExpression(String varName, CtExpression<?> expr) {
        try {
            // Build a minimal fake local variable scan by wrapping just this expression
            Optional<MapFact> toMap = tryCollectorsToMap(varName, expr);
            if (toMap.isPresent()) return toMap;

            return tryGroupingBy(varName, expr);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Mirrors CollectorsToMapPattern logic for an isolated expression. */
    private Optional<MapFact> tryCollectorsToMap(String varName, CtExpression<?> expr) {
        // Walk expr looking for .collect(Collectors.toMap(keyRef, valueRef))
        CtExpression<?> current = expr;
        while (current instanceof spoon.reflect.code.CtInvocation<?> inv) {
            if ("collect".equals(inv.getExecutable().getSimpleName())
                    && !inv.getArguments().isEmpty()
                    && inv.getArguments().get(0) instanceof spoon.reflect.code.CtInvocation<?> toMap
                    && "toMap".equals(toMap.getExecutable().getSimpleName())
                    && toMap.getArguments().size() >= 2) {

                Optional<FieldProvenance> key   = ProvenanceResolver.fromMethodRef(toMap.getArguments().get(0));
                Optional<FieldProvenance> value = ProvenanceResolver.fromMethodRef(toMap.getArguments().get(1));
                if (key.isPresent() && value.isPresent()) {
                    return Optional.of(MapFact.of(varName, key.get(), value.get(), toMap.toString()));
                }
            }
            if (inv.getTarget() == null) break;
            current = inv.getTarget();
        }
        return Optional.empty();
    }

    /** Mirrors GroupingByPattern logic for an isolated expression. */
    private Optional<MapFact> tryGroupingBy(String varName, CtExpression<?> expr) {
        spoon.reflect.code.CtInvocation<?> current = expr instanceof spoon.reflect.code.CtInvocation<?> i ? i : null;
        while (current != null) {
            if ("collect".equals(current.getExecutable().getSimpleName())
                    && !current.getArguments().isEmpty()
                    && current.getArguments().get(0) instanceof spoon.reflect.code.CtInvocation<?> gb) {

                String callee = gb.getExecutable().getSimpleName();
                if (("groupingBy".equals(callee) || "partitioningBy".equals(callee))
                        && !gb.getArguments().isEmpty()) {
                    Optional<FieldProvenance> key = ProvenanceResolver.fromMethodRef(gb.getArguments().get(0));
                    if (key.isPresent()) {
                        FieldProvenance val = FieldProvenance.of(key.get().originClass(), "#self");
                        return Optional.of(MapFact.of(varName, key.get(), val, gb.toString()));
                    }
                }
            }
            current = current.getTarget() instanceof spoon.reflect.code.CtInvocation<?> t ? t : null;
        }
        return Optional.empty();
    }

    private boolean isMapType(CtTypeReference<?> typeRef) {
        if (typeRef == null) return false;
        String name = typeRef.getSimpleName();
        return name.equals("Map") || name.equals("HashMap") || name.equals("LinkedHashMap")
                || name.equals("TreeMap") || name.equals("ConcurrentHashMap")
                || name.equals("ConcurrentMap") || name.equals("SortedMap");
    }

    private boolean isStaticField(CtFieldWrite<?> fw, CtType<?> enclosingType) {
        try {
            return fw.getVariable().getDeclaration() instanceof CtField<?> f && f.isStatic();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isStaticFieldRef(CtFieldRead<?> fr, CtType<?> enclosingType) {
        try {
            return fr.getVariable().getDeclaration() instanceof CtField<?> f && f.isStatic();
        } catch (Exception e) {
            return false;
        }
    }
}
