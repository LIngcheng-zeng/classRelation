package org.example.analyzer.spoon.implicit;

import org.example.model.FieldProvenance;
import org.example.model.FieldMapping;
import org.example.model.MapFact;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Optional;

/**
 * Phase 1 pattern: extracts MapFacts from {@code Collectors.toMap(KeyGetter, ValueGetter)} calls.
 *
 * Recognized form:
 *   Map<K, V> varName = someStream.collect(Collectors.toMap(A::getX, A::getY));
 *
 * Produces:
 *   MapFact(varName, keyProvenance=FieldProvenance(A, x), valueProvenance=FieldProvenance(A, y))
 *
 * Phase 2 (bridge detection) is delegated to {@link ForEachGetBridgePattern},
 * which operates after all MapFacts are known.
 */
public class CollectorsToMapPattern implements KeyUsagePattern {

    @Override
    public void collectMapFacts(CtExecutable<?> method, ProvenanceContext ctx) {
        for (CtLocalVariable<?> lv : method.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (lv.getDefaultExpression() == null) continue;
            tryExtract(lv.getSimpleName(), lv.getDefaultExpression(), ctx);
        }
    }

    // Phase 2 is not the responsibility of this pattern.
    @Override
    public List<FieldMapping> detectBridges(CtExecutable<?> method, ProvenanceContext ctx) {
        return List.of();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void tryExtract(String varName, CtExpression<?> initExpr, ProvenanceContext ctx) {
        // Look for: <anything>.collect(Collectors.toMap(keyRef, valueRef))
        CtInvocation<?> collectCall = findCollectCall(initExpr);
        if (collectCall == null) return;

        if (collectCall.getArguments().isEmpty()) return;
        CtExpression<?> collectorArg = collectCall.getArguments().get(0);

        if (!(collectorArg instanceof CtInvocation<?> toMapCall)) return;
        if (!toMapCall.getExecutable().getSimpleName().equals("toMap")) return;
        if (toMapCall.getArguments().size() < 2) return;

        Optional<FieldProvenance> keyProv   = extractMethodRefProvenance(toMapCall.getArguments().get(0));
        Optional<FieldProvenance> valueProv = extractMethodRefProvenance(toMapCall.getArguments().get(1));

        if (keyProv.isEmpty() || valueProv.isEmpty()) return;

        MapFact fact = MapFact.of(varName, keyProv.get(), valueProv.get(), toMapCall.toString());
        ctx.registerMapFact(fact);

        // Also register key provenance for the Map variable itself so bridge detectors
        // can resolve it without re-parsing the init expression.
        ctx.registerVarProvenance(varName + "#key",   keyProv.get());
        ctx.registerVarProvenance(varName + "#value", valueProv.get());
    }

    /**
     * Walks a method-chain expression looking for the innermost .collect(...) call.
     * Handles chains like: list.stream().filter(...).collect(...)
     */
    private CtInvocation<?> findCollectCall(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv)) return null;
        if (inv.getExecutable().getSimpleName().equals("collect")) return inv;
        if (inv.getTarget() != null) return findCollectCall(inv.getTarget());
        return null;
    }

    /**
     * Extracts a FieldProvenance from a method reference expression like {@code Enterprise::getName}.
     *
     * Returns FieldProvenance(declaringType, fieldName) if the method reference is a getter.
     */
    private Optional<FieldProvenance> extractMethodRefProvenance(CtExpression<?> expr) {
        if (!(expr instanceof CtExecutableReferenceExpression<?, ?> refExpr)) return Optional.empty();

        CtExecutableReference<?> execRef = refExpr.getExecutable();
        String methodName = execRef.getSimpleName();

        if (!isGetter(methodName)) return Optional.empty();

        String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);

        String declaringClass = null;
        try {
            if (execRef.getDeclaringType() != null) {
                declaringClass = execRef.getDeclaringType().getSimpleName();
            }
        } catch (Exception ignored) {}

        if (declaringClass == null) return Optional.empty();

        return Optional.of(FieldProvenance.of(declaringClass, fieldName));
    }

    private boolean isGetter(String name) {
        return name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3));
    }
}
