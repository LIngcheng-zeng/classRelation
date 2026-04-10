package org.example.analyzer.spoon;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Extracts inter-procedural field mappings by projecting caller argument expressions
 * into the parameter names of the called method.
 *
 * Algorithm per method M with aliasMap A:
 *   1. Find all CtInvocations in M's body.
 *   2. For each invocation, resolve callee's CtExecutable declaration.
 *   3. Build a "projected alias map": callee_param_name → resolved_arg_expression.
 *      An arg is "resolvable" if it traces (via A) to a field read (obj.field or getter).
 *   4. Analyze the callee body (setter calls + field assignments) using the projected map.
 *   5. Recurse into the callee (depth-limited, cycle-guarded).
 *
 * Example:
 *   Caller:  orderId = order.orderId;  generateInvoice(user, orderId);
 *   Callee:  generateInvoice(User user, String orderId) { invoice.setRefOrderId(orderId); }
 *   Output:  FieldMapping(Order.orderId → Invoice.refOrderId, WRITE_ASSIGNMENT)
 */
class CallProjectionExtractor {

    private static final Logger log = Logger.getLogger(CallProjectionExtractor.class.getName());

    /** Maximum call-chain depth to follow. Prevents explosion on deep / recursive code. */
    private static final int MAX_DEPTH = 3;

    private final List<FieldMapping> results = new ArrayList<>();

    /**
     * Entry point: analyze one method in the context of its own alias map.
     *
     * @param method   the method to inspect for outbound calls
     * @param aliasMap local variable alias map for {@code method} (built by SpoonAliasBuilder)
     */
    void extract(CtExecutable<?> method, Map<String, CtExpression<?>> aliasMap) {
        Set<String> visited = new HashSet<>();
        processInvocations(method, aliasMap, 0, visited);
    }

    List<FieldMapping> results() {
        return results;
    }

    // -------------------------------------------------------------------------

    private void processInvocations(CtExecutable<?> method,
                                     Map<String, CtExpression<?>> aliasMap,
                                     int depth,
                                     Set<String> visited) {
        if (depth > MAX_DEPTH) return;

        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutable<?> callee = resolveCallee(inv);
            if (callee == null) continue;

            String calleeKey = calleeKey(callee);
            if (visited.contains(calleeKey)) continue;  // cycle guard

            Map<String, CtExpression<?>> projected = buildProjectedAlias(inv, callee, aliasMap);
            if (projected.isEmpty()) continue;  // no field provenance crossed the boundary

            // Extract FieldMappings from the callee body using the projected alias map
            extractFromCallee(callee, projected);

            // Recurse into callee with projected map
            Set<String> childVisited = new HashSet<>(visited);
            childVisited.add(calleeKey);
            processInvocations(callee, projected, depth + 1, childVisited);
        }
    }

    // -------------------------------------------------------------------------
    // Callee extraction (setter calls + field assignments inside callee body)
    // -------------------------------------------------------------------------

    private void extractFromCallee(CtExecutable<?> callee,
                                    Map<String, CtExpression<?>> projectedAlias) {
        String location = callee.getSimpleName() + "(projected)";

        // Pattern 1: obj.setXxx(expr)
        for (CtInvocation<?> inv : callee.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!isSetter(inv)) continue;

            String rawName   = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);

            CtExpression<?> receiver = inv.getTarget();
            CtExpression<?> value    = inv.getArguments().get(0);

            String     sinkClass  = resolveClassName(receiver);
            FieldRef   sinkRef    = new FieldRef(sinkClass, fieldName);
            ExpressionSide sinkSide = new ExpressionSide(List.of(sinkRef), "direct");

            ExpressionSide sourceSide = extractSourceSide(value, projectedAlias);
            if (!isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(
                    sourceSide, sinkSide,
                    MappingType.PARAMETERIZED,
                    MappingMode.WRITE_ASSIGNMENT,
                    inv.toString(),
                    location));
        }

        // Pattern 2: target.field = value  (CtFieldWrite on LHS)
        for (spoon.reflect.code.CtAssignment<?, ?> assign
                : callee.getElements(new TypeFilter<>(spoon.reflect.code.CtAssignment.class))) {

            if (!(assign.getAssigned() instanceof CtFieldWrite<?> fw)) continue;

            String     fieldName  = fw.getVariable().getSimpleName();
            String     sinkClass  = resolveClassName(fw.getTarget());
            FieldRef   sinkRef    = new FieldRef(sinkClass, fieldName);
            ExpressionSide sinkSide = new ExpressionSide(List.of(sinkRef), "direct");

            ExpressionSide sourceSide = extractSourceSide(assign.getAssignment(), projectedAlias);
            if (!isValidPair(sourceSide, sinkSide)) continue;

            results.add(new FieldMapping(
                    sourceSide, sinkSide,
                    MappingType.PARAMETERIZED,
                    MappingMode.WRITE_ASSIGNMENT,
                    assign.toString(),
                    location));
        }
    }

    // -------------------------------------------------------------------------
    // Source side extraction: resolve an expression to a set of FieldRefs
    // -------------------------------------------------------------------------

    private ExpressionSide extractSourceSide(CtExpression<?> expr,
                                              Map<String, CtExpression<?>> aliasMap) {
        List<FieldRef> refs = new ArrayList<>();
        collectFieldRefs(expr, refs, aliasMap, new HashSet<>());
        return new ExpressionSide(refs, "direct");
    }

    private void collectFieldRefs(CtExpression<?> expr, List<FieldRef> refs,
                                   Map<String, CtExpression<?>> aliasMap, Set<String> visited) {
        if (expr instanceof CtFieldRead<?> fr) {
            // obj.field
            String className = resolveClassName(fr.getTarget());
            refs.add(new FieldRef(className, fr.getVariable().getSimpleName()));

        } else if (expr instanceof CtVariableRead<?> vr) {
            String varName = vr.getVariable().getSimpleName();
            // Alias expansion with cycle guard
            CtExpression<?> aliased = aliasMap.get(varName);
            if (aliased != null && !visited.contains(varName)) {
                visited.add(varName);
                collectFieldRefs(aliased, refs, aliasMap, visited);
            }
            // No alias and not resolvable to a field → skip (no FieldRef with null class)

        } else if (expr instanceof CtInvocation<?> inv && isGetter(inv)) {
            // obj.getXxx()
            String raw       = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            String className = resolveClassName(inv.getTarget());
            refs.add(new FieldRef(className, fieldName));
        }
        // Other expression types (literals, binary ops, etc.) — ignored
    }

    // -------------------------------------------------------------------------
    // Projected alias map construction
    // -------------------------------------------------------------------------

    /**
     * For each callee parameter, if the corresponding call argument traces to a field
     * reference (directly or via the caller's alias map), record the mapping.
     * Parameters whose arguments carry no field provenance are excluded.
     */
    private Map<String, CtExpression<?>> buildProjectedAlias(CtInvocation<?> inv,
                                                               CtExecutable<?> callee,
                                                               Map<String, CtExpression<?>> callerAlias) {
        Map<String, CtExpression<?>> projected = new LinkedHashMap<>();

        List<CtParameter<?>> params = callee.getParameters();
        List<CtExpression<?>> args  = (List<CtExpression<?>>) (List<?>) inv.getArguments();

        int limit = Math.min(params.size(), args.size());
        for (int i = 0; i < limit; i++) {
            CtExpression<?> arg         = args.get(i);
            CtExpression<?> resolvedArg = resolveAlias(arg, callerAlias, new HashSet<>());

            // Only project if the resolved arg carries field provenance
            if (hasFieldProvenance(resolvedArg)) {
                projected.put(params.get(i).getSimpleName(), resolvedArg);
            }
        }

        return projected;
    }

    /**
     * Expands a variable read through the alias map to its originating expression.
     * Returns the original expression if no alias exists.
     */
    private CtExpression<?> resolveAlias(CtExpression<?> expr,
                                          Map<String, CtExpression<?>> aliasMap,
                                          Set<String> visited) {
        if (expr instanceof CtVariableRead<?> vr) {
            String name = vr.getVariable().getSimpleName();
            if (!visited.contains(name) && aliasMap.containsKey(name)) {
                visited.add(name);
                return resolveAlias(aliasMap.get(name), aliasMap, visited);
            }
        }
        return expr;
    }

    /**
     * Returns true if the expression is (or contains) a direct field access or getter call —
     * i.e., it carries provenance from a specific object's field.
     */
    private boolean hasFieldProvenance(CtExpression<?> expr) {
        if (expr instanceof CtFieldRead<?>) return true;
        if (expr instanceof CtInvocation<?> inv && isGetter(inv)) return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CtExecutable<?> resolveCallee(CtInvocation<?> inv) {
        try {
            CtExecutable<?> decl = inv.getExecutable().getDeclaration();
            if (decl == null || decl.getBody() == null) return null;
            return decl;
        } catch (Exception e) {
            return null;
        }
    }

    private String calleeKey(CtExecutable<?> callee) {
        String typeName = (callee instanceof spoon.reflect.declaration.CtTypeMember tm
                           && tm.getDeclaringType() != null)
                ? tm.getDeclaringType().getQualifiedName()
                : "?";
        return typeName + "#" + callee.getSignature();
    }

    private String resolveClassName(CtExpression<?> target) {
        if (target == null) return null;
        try {
            String qualified = target.getType().getQualifiedName();
            int dot = qualified.lastIndexOf('.');
            return dot >= 0 ? qualified.substring(dot + 1) : qualified;
        } catch (Exception e) {
            return target.toString();
        }
    }

    private boolean isSetter(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.startsWith("set")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && inv.getArguments().size() == 1
                && inv.getTarget() != null;
    }

    private boolean isGetter(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.startsWith("get")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && inv.getArguments().isEmpty()
                && inv.getTarget() != null;
    }

    private boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }
}
