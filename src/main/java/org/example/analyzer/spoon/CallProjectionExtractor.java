package org.example.analyzer.spoon;

import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtField;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.CtModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(CallProjectionExtractor.class);

    /** Maximum call-chain depth to follow. Prevents explosion on deep / recursive code. */
    private static final int MAX_DEPTH = 3;

    private final List<FieldMapping> results = new ArrayList<>();

    /** Spoon model used for Lombok-aware getter return type resolution. May be null. */
    private CtModel model;

    /**
     * Entry point: analyze one method in the context of its own alias map.
     *
     * @param method   the method to inspect for outbound calls
     * @param aliasMap local variable alias map for {@code method} (built by SpoonAliasBuilder)
     * @param model    Spoon's CtModel for resolving Lombok-generated getters
     */
    void extract(CtExecutable<?> method, Map<String, CtExpression<?>> aliasMap, CtModel model) {
        this.model = model;
        Set<String> visited = new HashSet<>();
        
        // Extract composition/aggregation relationships (A holds reference to B)
        extractCompositionRelationships(method, aliasMap);
        
        // Extract constructor calls directly from this method
        extractConstructorCalls(method, aliasMap);
        
        // Process inter-procedural calls
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
        
        // Pattern 3: Constructor calls - new ClassName(arg1, arg2, ...)
        // Extract field mappings from constructor arguments to constructor parameters
        extractConstructorCalls(callee, projectedAlias);
    }
    
    /**
     * Extracts composition/aggregation relationships where one class holds a reference to another.
     * For example: userOrderDTO.getOrderDTO() indicates UserOrderDTO holds OrderDTO
     */
    private void extractCompositionRelationships(CtExecutable<?> method,
                                                  Map<String, CtExpression<?>> aliasMap) {
        String location = method.getSimpleName() + "(composition)";
        
        // Find all getter invocations that return custom types
        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!isGetter(inv)) continue;
            
            try {
                // Get the receiver class (the holder)
                CtExpression<?> target = inv.getTarget();
                if (target == null) continue;
                
                String holderClass = resolveClassName(target);
                if (holderClass == null || isSystemClass(holderClass)) continue;
                
                // Get the return type of the getter (the held class)
                String heldClass = resolveGetterReturnType(inv);
                if (heldClass == null || isSystemClass(heldClass)) continue;
                
                // Don't create self-references
                if (holderClass.equals(heldClass)) continue;
                
                // Create a composition relationship
                FieldRef holderRef = new FieldRef(holderClass, "holds");
                FieldRef heldRef = new FieldRef(heldClass, "held");
                
                ExpressionSide sourceSide = new ExpressionSide(List.of(holderRef), "composition-source");
                ExpressionSide sinkSide = new ExpressionSide(List.of(heldRef), "composition-sink");
                
                results.add(new FieldMapping(
                        sourceSide, sinkSide,
                        MappingType.PARAMETERIZED,
                        MappingMode.WRITE_ASSIGNMENT,
                        inv.toString(),
                        location));
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * Checks if a class name represents a system/JDK class (not user-defined).
     */
    private boolean isSystemClass(String className) {
        if (className == null) return true;
        
        // Common system packages
        return className.startsWith("java.") 
            || className.startsWith("javax.")
            || className.startsWith("sun.")
            || className.equals("String")
            || className.equals("Integer")
            || className.equals("Long")
            || className.equals("Boolean")
            || className.equals("Double")
            || className.equals("Float")
            || className.equals("Character")
            || className.equals("Byte")
            || className.equals("Short")
            || className.equals("Object")
            || className.equals("List")
            || className.equals("Map")
            || className.equals("Set")
            || className.equals("ArrayList")
            || className.equals("HashMap")
            || className.equals("HashSet")
            || className.equals("Optional")
            || className.equals("Stream");
    }

    /**
     * Extracts field mappings from constructor invocations.
     * For example: new Account(user.getPhone(), user.getId())
     * Maps: User.phone → Account.fullMobile, User.id → Account.userId
     */
    private void extractConstructorCalls(CtExecutable<?> method,
                                         Map<String, CtExpression<?>> aliasMap) {
        String location = method.getSimpleName() + "(constructor-call)";
        
        for (spoon.reflect.code.CtConstructorCall<?> ctorCall 
                : method.getElements(new TypeFilter<>(spoon.reflect.code.CtConstructorCall.class))) {
            
            try {
                // Get the constructed type
                String sinkClass = resolveClassName(ctorCall);
                if (sinkClass == null || sinkClass.equals("null")) continue;
                
                // Get constructor parameters (if available in model)
                spoon.reflect.reference.CtExecutableReference<?> execRef = ctorCall.getExecutable();
                spoon.reflect.declaration.CtConstructor<?> ctorDecl = null;
                try {
                    spoon.reflect.declaration.CtExecutable<?> decl = execRef.getDeclaration();
                    if (decl instanceof spoon.reflect.declaration.CtConstructor<?>) {
                        ctorDecl = (spoon.reflect.declaration.CtConstructor<?>) decl;
                    }
                } catch (Exception ignored) {}
                
                List<CtExpression<?>> args = ctorCall.getArguments();
                
                // If we have constructor declaration with parameter names
                if (ctorDecl != null) {
                    List<CtParameter<?>> params = ctorDecl.getParameters();
                    int limit = Math.min(params.size(), args.size());
                    
                    for (int i = 0; i < limit; i++) {
                        String paramName = params.get(i).getSimpleName();
                        CtExpression<?> arg = args.get(i);
                        
                        ExpressionSide sourceSide = extractSourceSide(arg, aliasMap);
                        if (sourceSide.isEmpty()) continue;
                        
                        // Create a FieldRef for the constructor parameter as the sink
                        FieldRef sinkRef = new FieldRef(sinkClass, paramName);
                        ExpressionSide sinkSide = new ExpressionSide(List.of(sinkRef), "constructor-param");
                        
                        if (isValidPair(sourceSide, sinkSide)) {
                            results.add(new FieldMapping(
                                    sourceSide, sinkSide,
                                    MappingType.PARAMETERIZED,
                                    MappingMode.WRITE_ASSIGNMENT,
                                    ctorCall.toString(),
                                    location));
                        }
                    }
                } else {
                    // Fallback: map arguments by position without parameter names
                    for (int i = 0; i < args.size(); i++) {
                        CtExpression<?> arg = args.get(i);
                        ExpressionSide sourceSide = extractSourceSide(arg, aliasMap);
                        if (sourceSide.isEmpty()) continue;
                        
                        // Use generic parameter name like "param0", "param1"
                        FieldRef sinkRef = new FieldRef(sinkClass, "param" + i);
                        ExpressionSide sinkSide = new ExpressionSide(List.of(sinkRef), "constructor-param");
                        
                        if (isValidPair(sourceSide, sinkSide)) {
                            results.add(new FieldMapping(
                                    sourceSide, sinkSide,
                                    MappingType.PARAMETERIZED,
                                    MappingMode.WRITE_ASSIGNMENT,
                                    ctorCall.toString(),
                                    location));
                        }
                    }
                }
            } catch (Exception ignored) {}
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
            // obj.field - handle chain scenarios like orderDTO.getOrder().orderId
            String className = resolveClassNameFromFieldRef(fr);
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
            // obj.getXxx() - handle chain scenarios like a.getB().getC()
            String raw       = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            // Get the class that declares this getter (the receiver's type), not the return type
            String className = resolveClassName(inv.getTarget());
            refs.add(new FieldRef(className, fieldName));
            
            // DO NOT recursively process the target here
            // The composition relationships are handled separately by extractCompositionRelationships
            // This ensures we only map direct field access, not indirect holdings
            
        } else if (expr instanceof CtInvocation<?> inv && isMonadicMethod(inv)) {
            // Handle Optional/Stream monadic chains: optional.map(User::getId)
            // Extract field refs from arguments (e.g., method references or lambdas)
            for (CtExpression<?> arg : inv.getArguments()) {
                collectFieldRefs(arg, refs, aliasMap, visited);
            }
            // Also check the target (the Optional/Stream itself)
            CtExpression<?> target = inv.getTarget();
            if (target != null) {
                collectFieldRefs(target, refs, aliasMap, visited);
            }
            
        } else if (expr instanceof spoon.reflect.code.CtLambda<?> lambda) {
            // Handle lambda expressions: user -> user.getId()
            try {
                for (spoon.reflect.code.CtStatement stmt : lambda.getBody().getStatements()) {
                    if (stmt instanceof CtExpression<?> stmtExpr) {
                        collectFieldRefs(stmtExpr, refs, aliasMap, visited);
                    }
                }
            } catch (Exception ignored) {}
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
        
        // Check for chained getters like a.getB().getC()
        if (expr instanceof CtInvocation<?> inv) {
            CtExpression<?> target = inv.getTarget();
            while (target instanceof CtInvocation<?> targetInv) {
                if (isGetter(targetInv)) return true;
                target = targetInv.getTarget();
            }
            if (target instanceof CtFieldRead<?>) return true;
        }
        
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the declaring class name from a CtFieldRead.
     * For chain scenarios like orderDTO.getOrder().orderId:
     *   - fr.getVariable().getDeclaringType() directly gets the field's declaring class
     *   - Fallback to resolveClassName(target) if declaring type is unavailable
     */
    private String resolveClassNameFromFieldRef(CtFieldRead<?> fr) {
        try {
            // Direct approach: get the field's declaring type (skips chain calls)
            spoon.reflect.reference.CtTypeReference<?> declaringType = fr.getVariable().getDeclaringType();
            if (declaringType != null) {
                String qualified = declaringType.getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception ignored) {}
        
        // Fallback: resolve from target expression
        return resolveClassName(fr.getTarget());
    }

    /**
     * Unwraps Builder or Monadic (Optional/Stream) types to find the actual contained type.
     * 
     * For Builder patterns:
     *   - Invoice.builder().buyerId(x).build() → unwrap to Invoice
     *   - Looks for build()/buildXxx() methods and returns their return type
     * 
     * For Monadic patterns (Optional/Stream):
     *   - optional.map(User::getId) → unwrap Optional<User> to User
     *   - Uses generic type parameters when available
     */
    private String unwrapBuilderOrMonadicType(CtInvocation<?> inv) {
        String methodName = inv.getExecutable().getSimpleName();
        
        // Strategy 1: For builder.build() - return the build method's return type
        if (methodName.equals("build") || methodName.startsWith("build")) {
            try {
                spoon.reflect.reference.CtTypeReference<?> returnType = inv.getExecutable().getType();
                if (returnType != null) {
                    String qualified = returnType.getQualifiedName();
                    int dot = qualified.lastIndexOf('.');
                    return dot >= 0 ? qualified.substring(dot + 1) : qualified;
                }
            } catch (Exception ignored) {}
        }
        
        // Strategy 2: For monadic operations (map, flatMap, etc.) - extract generic type
        if (isMonadicMethod(inv)) {
            try {
                // Try to get the generic type argument from Optional<T> or Stream<T>
                spoon.reflect.reference.CtTypeReference<?> type = inv.getType();
                if (type != null && type.getActualTypeArguments() != null && !type.getActualTypeArguments().isEmpty()) {
                    // Get the first type argument (e.g., T from Optional<T>)
                    spoon.reflect.reference.CtTypeReference<?> typeArg = type.getActualTypeArguments().get(0);
                    String qualified = typeArg.getQualifiedName();
                    int dot = qualified.lastIndexOf('.');
                    return dot >= 0 ? qualified.substring(dot + 1) : qualified;
                }
            } catch (Exception ignored) {}
        }
        
        // Strategy 3: For builder chains, try to infer from the target's type
        // e.g., Invoice.builder() → look at what builder() was called on
        try {
            CtExpression<?> target = inv.getTarget();
            if (target instanceof CtInvocation<?> targetInv) {
                String targetMethod = targetInv.getExecutable().getSimpleName();
                if (targetMethod.equals("builder") || targetMethod.endsWith("Builder")) {
                    // The builder was created from a class, try to get that class
                    spoon.reflect.reference.CtTypeReference<?> targetType = targetInv.getType();
                    if (targetType != null) {
                        // Remove "Builder" suffix to get the actual class name
                        String builderName = targetType.getSimpleName();
                        if (builderName.endsWith("Builder")) {
                            String actualClass = builderName.substring(0, builderName.length() - 7);
                            return actualClass;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        
        return null; // Cannot unwrap
    }

    /**
     * Finds a field's type by searching through the class hierarchy (including superclasses).
     * Supports cross-file inheritance where the superclass may be defined in a different file.
     * 
     * @param type The class to start searching from
     * @param fieldName The field name to look for
     * @return The field's type simple name, or null if not found
     */
    private String findFieldTypeInHierarchy(spoon.reflect.declaration.CtType<?> type, String fieldName) {
        // Use a set to prevent infinite loops in case of circular inheritance
        Set<String> visitedTypes = new HashSet<>();
        spoon.reflect.declaration.CtType<?> currentType = type;
        
        while (currentType != null && !visitedTypes.contains(currentType.getQualifiedName())) {
            visitedTypes.add(currentType.getQualifiedName());
            
            // Search in current type's fields
            for (CtField<?> field : currentType.getFields()) {
                if (field.getSimpleName().equals(fieldName)) {
                    String fieldType = field.getType().getQualifiedName();
                    int dot = fieldType.lastIndexOf('.');
                    return dot >= 0 ? fieldType.substring(dot + 1) : fieldType;
                }
            }
            
            // Move to superclass
            try {
                spoon.reflect.reference.CtTypeReference<?> superClassRef = currentType.getSuperclass();
                if (superClassRef == null) break;
                
                String superClassName = superClassRef.getSimpleName();
                // Skip java.lang.Object
                if ("Object".equals(superClassName)) break;
                
                // Find the superclass in the model
                spoon.reflect.declaration.CtType<?> superClass = null;
                for (spoon.reflect.declaration.CtType<?> t : model.getAllTypes()) {
                    if (t.getSimpleName().equals(superClassName)) {
                        superClass = t;
                        break;
                    }
                }
                currentType = superClass;
            } catch (Exception e) {
                break; // Cannot resolve superclass
            }
        }
        
        return null; // Field not found in hierarchy
    }

    /**
     * Resolves the return type of a getter invocation, supporting both:
     *   1. Regular getters: inv.getExecutable().getDeclaration().getType()
     *   2. Lombok-generated getters: recursively resolve receiver class and lookup field
     *
     * Supports arbitrary depth chains like a.getB().getC().getField()
     */
    private String resolveGetterReturnType(CtInvocation<?> inv) {
        // Step 1: Try direct resolution from executable's type reference (works for Lombok in compiled form)
        try {
            spoon.reflect.reference.CtTypeReference<?> returnType = inv.getExecutable().getType();
            if (returnType != null) {
                String qualified = returnType.getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception ignored) {}

        // Step 2: Try from method declaration (non-Lombok methods in source code)
        try {
            spoon.reflect.declaration.CtExecutable<?> decl = inv.getExecutable().getDeclaration();
            if (decl != null && decl instanceof spoon.reflect.declaration.CtMethod<?>) {
                spoon.reflect.reference.CtTypeReference<?> returnType = ((spoon.reflect.declaration.CtMethod<?>) decl).getType();
                if (returnType != null) {
                    String qualified = returnType.getQualifiedName();
                    int dot = qualified.lastIndexOf('.');
                    return dot >= 0 ? qualified.substring(dot + 1) : qualified;
                }
            }
        } catch (Exception ignored) {}

        // Step 3: Lombok fallback - resolve receiver class and find field by name (including inherited fields)
        try {
            String rawName = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(rawName.charAt(3)) + rawName.substring(4);
            String receiverClass = resolveClassName(inv.getTarget());
            
            if (receiverClass != null && model != null) {
                // Search for the field in the receiver class and its superclasses
                for (spoon.reflect.declaration.CtType<?> type : model.getAllTypes()) {
                    String typeName = type.getSimpleName();
                    if (typeName.equals(receiverClass)) {
                        // Search in the class itself
                        String fieldType = findFieldTypeInHierarchy(type, fieldName);
                        if (fieldType != null) return fieldType;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Final fallback: use getType() on the invocation itself
        try {
            spoon.reflect.reference.CtTypeReference<?> type = inv.getType();
            if (type != null) {
                String qualified = type.getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception ignored) {}

        // Last resort fallback
        return resolveClassName(inv.getTarget());
    }

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

    /**
     * Resolves the class name from a target expression.
     * Supports arbitrary depth chain calls by mutual recursion with resolveGetterReturnType:
     *   - CtFieldRead: delegates to resolveClassNameFromFieldRef
     *   - CtInvocation (getter): delegates to resolveGetterReturnType
     *   - CtInvocation (builder/monadic): follows the chain to find actual type
     *   - Other expressions: uses getType() directly
     */
    private String resolveClassName(CtExpression<?> target) {
        if (target == null) return null;
        
        // Handle chain scenarios recursively
        if (target instanceof CtFieldRead<?> fr) {
            return resolveClassNameFromFieldRef(fr);
        }
        if (target instanceof CtInvocation<?> inv) {
            // Getter: resolve to the receiver's class
            if (isGetter(inv)) {
                return resolveGetterReturnType(inv);
            }
            // Builder/Monadic: try to unwrap to find the actual contained type
            if (isBuilderMethod(inv) || isMonadicMethod(inv)) {
                String unwrapped = unwrapBuilderOrMonadicType(inv);
                if (unwrapped != null) return unwrapped;
            }
            // For other invocations, fall through to direct type resolution
        }
        
        // Direct type resolution for non-chain expressions
        try {
            spoon.reflect.reference.CtTypeReference<?> typeRef = target.getType();
            if (typeRef != null) {
                // For generic types like List<User>, try to extract the type argument
                if (typeRef.getActualTypeArguments() != null && !typeRef.getActualTypeArguments().isEmpty()) {
                    // Use the first type argument (e.g., User from List<User>)
                    spoon.reflect.reference.CtTypeReference<?> typeArg = typeRef.getActualTypeArguments().get(0);
                    String qualified = typeArg.getQualifiedName();
                    int dot = qualified.lastIndexOf('.');
                    return dot >= 0 ? qualified.substring(dot + 1) : qualified;
                }
                // Fallback to the main type
                String qualified = typeRef.getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception e) {
            return target.toString();
        }
        
        // Final fallback if getType() returns null
        return target.toString();
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

    /**
     * Checks if an invocation is a builder-style method (returns same or related type).
     * Supports: builder(), build(), xxxBuilder(), withXxx(), etc.
     */
    private boolean isBuilderMethod(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        // Common builder patterns
        return name.equals("builder") 
            || name.equals("build")
            || name.endsWith("Builder")
            || (name.startsWith("with") && name.length() > 4 && Character.isUpperCase(name.charAt(4)))
            || (name.startsWith("set") && inv.getTarget() != null); // setter in builder chain
    }

    /**
     * Checks if an invocation is an Optional/Monadic method.
     * Supports: map(), flatMap(), filter(), orElse(), orElseGet(), etc.
     */
    private boolean isMonadicMethod(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.equals("map") 
            || name.equals("flatMap")
            || name.equals("filter")
            || name.equals("orElse")
            || name.equals("orElseGet")
            || name.equals("orElseThrow")
            || name.equals("ifPresent")
            || name.equals("ifPresentOrElse");
    }

    private boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }
}
