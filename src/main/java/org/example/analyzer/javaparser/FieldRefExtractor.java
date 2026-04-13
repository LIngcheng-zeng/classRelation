package org.example.analyzer.javaparser;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.example.model.ExpressionSide;
import org.example.model.FieldRef;
import org.example.util.ClassNameResolver;
import org.example.util.ClassNameResolverChain;
import org.example.util.ClassNameValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts field references from a JavaParser expression.
 *
 * Recognized patterns:
 *   - obj.field                          → direct field access
 *   - String.concat / "+" concatenation  → composite
 *   - method chains like transform()      → parameterized
 *   - Container access: list.get(0), map.get(key), optional.get(), etc.
 *   - Array access: arr[i], arr[i].getField()
 *
 * Scope resolution (class name from an expression) is handled by a
 * {@link ClassNameResolverChain} built once at construction time:
 *
 *   1. AliasExpansionResolver     — expands NameExpr via alias map, recurses
 *   2. SymbolSolverResolver       — JavaParser SymbolSolver (calculateResolvedType)
 *   3. ContainerAccessResolver    — resolves container access patterns (get, poll, pop, next, etc.)
 *   4. GetterChainResolver        — recursive resolution for getter chains a.getB().getC()
 *   5. HeuristicScopeResolver     — builder root, getter SymbolSolver, capitalization fallback
 */
public class FieldRefExtractor {

    private static final Logger log = LoggerFactory.getLogger(FieldRefExtractor.class);

    /** Hybrid type resolver combining Spoon and JavaParser capabilities */
    private final HybridTypeResolver hybridResolver;

    /** Resolution chain built once; all steps are stateless method references on {@code this}. */
    private final ClassNameResolverChain<ScopeContext> scopeChain;

    /**
     * Default constructor without Spoon model (uses only JavaParser).
     * For full type inference with generics, use the constructor with CtModel.
     */
    public FieldRefExtractor() {
        this.hybridResolver = null;
        this.scopeChain = buildScopeChain();
    }

    /**
     * Constructor with Spoon model and classPackageIndex for full hybrid type inference.
     *
     * @param spoonModel         Spoon's CtModel for static type analysis
     * @param classPackageIndex  simpleName → FQN index (from SymbolResolutionResult)
     */
    public FieldRefExtractor(spoon.reflect.CtModel spoonModel, java.util.Map<String, String> classPackageIndex) {
        this.hybridResolver = new HybridTypeResolver(spoonModel, classPackageIndex);
        this.scopeChain = buildScopeChain();
    }

    /**
     * Constructor with Spoon model only (classPackageIndex unavailable).
     * Prefer the two-argument constructor when classPackageIndex is available.
     *
     * @param spoonModel Spoon's CtModel for static type analysis
     */
    public FieldRefExtractor(spoon.reflect.CtModel spoonModel) {
        this.hybridResolver = new HybridTypeResolver(spoonModel, java.util.Map.of());
        this.scopeChain = buildScopeChain();
    }

    /**
     * Builds the resolution chain. Must be called after hybridResolver is initialized.
     */
    private ClassNameResolverChain<ScopeContext> buildScopeChain() {
        return ClassNameResolverChain.of(
                this::resolveViaAliasExpansion,
                this::resolveViaSymbolSolver,
                this::resolveViaHybridTypeInference,  // NEW: hybrid Spoon+JavaParser
                this::resolveViaArrayAccess,          // NEW: array access arr[i]
                this::resolveViaContainerAccess,
                this::resolveViaGetterChain,
                this::resolveViaHeuristic
        );
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ExpressionSide extract(Expression expr) {
        return extract(expr, Collections.emptyMap());
    }

    public ExpressionSide extract(Expression expr, Map<String, Expression> aliasMap) {
        Expression expanded = expandAliases(expr, aliasMap, new HashSet<>());
        String     operator = detectOperator(expanded);
        List<FieldRef> refs = new ArrayList<>();
        collectFieldRefs(expanded, refs, aliasMap, new HashSet<>());
        return new ExpressionSide(refs, operator);
    }

    public List<String> extractNormalization(Expression expr) {
        List<String> normalizations = new ArrayList<>();
        collectNormalizationOps(expr, normalizations);
        return normalizations;
    }

    /** Public entry point used by SetterMappingExtractor. */
    public String resolveClassNamePublic(Expression scope, Map<String, Expression> aliasMap) {
        return scopeChain.resolve(ScopeContext.of(scope, aliasMap, new HashSet<>()));
    }

    // -------------------------------------------------------------------------
    // Scope resolution chain steps
    // -------------------------------------------------------------------------

    /**
     * Step 1 — expand a NameExpr through the alias map.
     * Only returns a result if the expanded alias resolves to a valid class name;
     * builder chains like {@code Address.builder()...build()} produce "build()" which
     * is not a valid class name, so we fall through to SymbolSolver in that case.
     */
    private String resolveViaAliasExpansion(ScopeContext ctx) {
        if (!(ctx.scope() instanceof NameExpr ne)) return null;

        String varName = ne.getNameAsString();
        if (ctx.visited().contains(varName)) return null;

        Expression aliased = ctx.aliasMap().get(varName);
        if (aliased == null) return null;

        ctx.visited().add(varName);
        String resolved = scopeChain.resolve(ScopeContext.of(aliased, ctx.aliasMap(), ctx.visited()));
        return ClassNameValidator.isValidClassName(resolved) ? resolved : null;
    }

    /**
     * Step 2 — ask JavaParser's SymbolSolver for the precise reference type.
     */
    private String resolveViaSymbolSolver(ScopeContext ctx) {
        try {
            ResolvedType resolvedType = ctx.scope().calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                return resolvedType.asReferenceType().getQualifiedName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Step 3 — hybrid type inference combining Spoon and JavaParser.
     * Uses Spoon's static analysis to infer types when JavaParser SymbolSolver fails,
     * especially for local variables with generic types like List<T>, Map<K,V>.
     */
    private String resolveViaHybridTypeInference(ScopeContext ctx) {
        if (hybridResolver == null) {
            return null;  // Fallback to next resolver if hybrid not available
        }

        String typeName = hybridResolver.resolveType(ctx.scope(), ctx.aliasMap());
        if (typeName != null) {
            // Extract base type name without generics for class resolution
            int genericStart = typeName.indexOf('<');
            if (genericStart > 0) {
                return typeName.substring(0, genericStart);
            }
            return typeName;
        }

        return null;
    }

    /**
     * Step 3.5 — resolve array access expressions arr[i] to their component type.
     * This enables chaining like arr[i].getField() to work correctly.
     */
    private String resolveViaArrayAccess(ScopeContext ctx) {
        if (!(ctx.scope() instanceof ArrayAccessExpr aa)) return null;
        
        if (hybridResolver != null) {
            // Use hybrid resolver to get array component type
            String componentType = hybridResolver.resolveArrayComponentType(aa.getName(), ctx.aliasMap());
            if (componentType != null && ClassNameValidator.isValidClassName(componentType)) {
                log.debug("Hybrid resolved array component type: {}", componentType);
                return componentType;
            }
        }
        
        // Fallback to JavaParser SymbolSolver
        try {
            ResolvedType arrayType = aa.getName().calculateResolvedType();
            if (arrayType.isArray()) {
                ResolvedType componentType = arrayType.asArrayType().getComponentType();
                if (componentType.isReference()) {
                    return componentType.asReferenceType().getQualifiedName();
                }
            }
        } catch (Exception ignored) {}
        
        return null;
    }

    /**
     * Step 4 — handle container access patterns (Category A-E):
     *   A. list.get(0), map.get(key) → extract type argument T or V
     *   B. optional.get(), atomicRef.get() → extract type argument T
     *   C. queue.poll(), stack.pop(), iterator.next() → extract type argument T
     *   D. ArrayAccessExpr arr[i] → componentType (handled in collectFieldRefs)
     *   E. entry.getValue()/getKey() → Map.Entry<K,V> type arguments
     *
     * Strategy:
     *   1. Resolve receiver type via SymbolSolver OR Hybrid resolver
     *   2. Match method name to container pattern
     *   3. Extract appropriate type argument from generic parameters
     */
    private String resolveViaContainerAccess(ScopeContext ctx) {
        if (!(ctx.scope() instanceof MethodCallExpr mc)) return null;
        if (mc.getScope().isEmpty()) return null;

        String methodName = mc.getNameAsString();
        Expression receiverExpr = mc.getScope().get();

        // Try to resolve receiver type using hybrid strategy
        String receiverTypeName = null;
        com.github.javaparser.resolution.types.ResolvedReferenceType refType = null;

        // First attempt: JavaParser SymbolSolver
        try {
            ResolvedType resolvedType = receiverExpr.calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                refType = resolvedType.asReferenceType();
                receiverTypeName = refType.getQualifiedName();
            }
        } catch (Exception e) {
            log.trace("SymbolSolver failed for receiver: {}", e.getMessage());
        }

        // Second attempt: Hybrid resolver (Spoon + JavaParser)
        if (receiverTypeName == null && hybridResolver != null) {
            receiverTypeName = hybridResolver.resolveType(receiverExpr, ctx.aliasMap());
            if (receiverTypeName != null) {
                log.debug("Hybrid resolver inferred type: {}", receiverTypeName);
            }
        }

        if (receiverTypeName == null) {
            return null;
        }

        // If we have the type name but not the ResolvedReferenceType, use string-based extraction
        if (refType == null && hybridResolver != null) {
            // For container types, extract value type from generic signature
            if (hybridResolver.isContainerType(receiverTypeName)) {
                String valueType = hybridResolver.extractContainerValueType(receiverTypeName);
                if (valueType != null && ClassNameValidator.isValidClassName(valueType)) {
                    return valueType;
                }
            }
            return null;
        }

        // Use ResolvedReferenceType for precise type parameter extraction
        // Category A & B: get() methods
        if (methodName.equals("get")) {
            return resolveGetAccess(mc, refType, receiverTypeName);
        }

        // Category C: poll/peek/pop/next
        if (isContainerPopMethod(methodName)) {
            return resolvePopAccess(refType);
        }

        // Category E: Map.Entry getValue/getKey
        if (methodName.equals("getValue") || methodName.equals("getKey")) {
            return resolveMapEntryAccess(refType, methodName);
        }

        return null;
    }

    /**
     * Resolves get() access for List, Map, Optional, AtomicReference, etc.
     */
    private String resolveGetAccess(MethodCallExpr mc, com.github.javaparser.resolution.types.ResolvedReferenceType refType,
                                     String qualifiedName) {
        int argCount = mc.getArguments().size();

        try {
            // Category A: list.get(0) - 1 argument, take first type parameter
            if (argCount == 1 && isListOrMapLike(qualifiedName)) {
                var typeArgs = refType.typeParametersValues();
                if (qualifiedName.startsWith("java.util.List") || qualifiedName.startsWith("java.util.ArrayList")) {
                    // List<T>.get(int) → T
                    if (!typeArgs.isEmpty()) {
                        return typeArgs.get(0).describe();
                    }
                } else if (qualifiedName.startsWith("java.util.Map") || qualifiedName.startsWith("java.util.HashMap")) {
                    // Map<K,V>.get(K) → V
                    if (typeArgs.size() >= 2) {
                        return typeArgs.get(1).describe();
                    }
                }
                // User-defined Cache<K,V> or similar: assume last type param is value
                if (!typeArgs.isEmpty()) {
                    return typeArgs.get(typeArgs.size() - 1).describe();
                }
            }

            // Category B: optional.get() - 0 arguments, take first type parameter
            if (argCount == 0) {
                var typeArgs = refType.typeParametersValues();
                if (!typeArgs.isEmpty()) {
                    String typeArg = typeArgs.get(0).describe();
                    // Only return if it looks like a valid class (not primitive, not wildcard)
                    if (ClassNameValidator.isValidClassName(typeArg)) {
                        return typeArg;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Resolves poll/peek/pop/next access for Queue, Stack, Deque, Iterator.
     */
    private String resolvePopAccess(com.github.javaparser.resolution.types.ResolvedReferenceType refType) {
        try {
            var typeArgs = refType.typeParametersValues();
            if (!typeArgs.isEmpty()) {
                return typeArgs.get(0).describe();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolves Map.Entry.getValue()/getKey() access.
     */
    private String resolveMapEntryAccess(com.github.javaparser.resolution.types.ResolvedReferenceType refType,
                                          String methodName) {
        try {
            var typeArgs = refType.typeParametersValues();
            if (typeArgs.size() >= 2) {
                if (methodName.equals("getValue")) {
                    // Map.Entry<K,V>.getValue() → V
                    return typeArgs.get(1).describe();
                } else {
                    // Map.Entry<K,V>.getKey() → K
                    return typeArgs.get(0).describe();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Checks if the qualified name represents a List-like or Map-like container.
     */
    private boolean isListOrMapLike(String qualifiedName) {
        return qualifiedName.startsWith("java.util.List")
                || qualifiedName.startsWith("java.util.ArrayList")
                || qualifiedName.startsWith("java.util.LinkedList")
                || qualifiedName.startsWith("java.util.Map")
                || qualifiedName.startsWith("java.util.HashMap")
                || qualifiedName.startsWith("java.util.TreeMap")
                || qualifiedName.startsWith("java.util.LinkedHashMap");
    }

    /**
     * Checks if the method name is a container pop operation.
     */
    private boolean isContainerPopMethod(String methodName) {
        return methodName.equals("poll") || methodName.equals("peek")
                || methodName.equals("pop")
                || methodName.equals("pollFirst") || methodName.equals("pollLast")
                || methodName.equals("next");
    }

    /**
     * Step 4 — handle getter chains like {@code a.getB().getC()} where SymbolSolver
     * failed (typically Lombok-generated getters in source form).
     *
     * Strategy:
     *   a) Try SymbolSolver on the getter call expression itself.
     *   b) Recursively resolve the receiver class, then look up the field
     *      declared in that receiver type.
     */
    private String resolveViaGetterChain(ScopeContext ctx) {
        if (!(ctx.scope() instanceof MethodCallExpr mc) || !isGetter(mc)) return null;

        // a) SymbolSolver on the full getter expression
        try {
            ResolvedType type = mc.calculateResolvedType();
            if (type.isReferenceType()) {
                String qualified = type.asReferenceType().getQualifiedName();
                if (ClassNameValidator.isValidClassName(qualified)) return qualified;
            }
        } catch (Exception ignored) {}

        // b) Recursive receiver resolution + field lookup
        if (mc.getScope().isEmpty()) return null;

        Expression receiverExpr  = mc.getScope().get();
        String     receiverClass = scopeChain.resolve(
                ScopeContext.of(receiverExpr, ctx.aliasMap(), ctx.visited()));

        if (!ClassNameValidator.isValidClassName(receiverClass)) return null;

        try {
            String raw = mc.getNameAsString();
            String fn  = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);

            ResolvedType receiverType = receiverExpr.calculateResolvedType();
            if (receiverType.isReferenceType()) {
                var typeDecl = receiverType.asReferenceType().getTypeDeclaration().orElse(null);
                if (typeDecl != null) {
                    for (var field : typeDecl.getDeclaredFields()) {
                        if (field.getName().equals(fn)) {
                            String fieldType = field.getType().describe();
                            if (ClassNameValidator.isValidClassName(fieldType)) return fieldType;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Step 4 — heuristic fallbacks when all structured resolution has failed:
     *   - NameExpr: capitalize first letter (variable 'order' → class hint 'Order')
     *   - ObjectCreationExpr: type name
     *   - FieldAccessExpr: field name
     *   - MethodCallExpr: builder-root detection or getter SymbolSolver + field lookup
     *   - Other: toString()
     */
    private String resolveViaHeuristic(ScopeContext ctx) {
        Expression scope = ctx.scope();

        if (scope instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            if (name.isEmpty()) return name;
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        if (scope instanceof ObjectCreationExpr oce) {
            return oce.getTypeAsString();
        }
        if (scope instanceof FieldAccessExpr fa) {
            return fa.getNameAsString();
        }
        if (scope instanceof MethodCallExpr mc) {
            String builderClass = extractBuilderRootClass(mc);
            if (builderClass != null) return builderClass;

            if (isGetter(mc)) {
                // SymbolSolver attempt on the getter itself
                try {
                    ResolvedType type = mc.calculateResolvedType();
                    if (type.isReferenceType()) {
                        String qualified = type.asReferenceType().getQualifiedName();
                        if (ClassNameValidator.isValidClassName(qualified)) return qualified;
                    }
                } catch (Exception ignored) {}

                // Field lookup in receiver type
                if (mc.getScope().isPresent()) {
                    try {
                        String raw = mc.getNameAsString();
                        String fn  = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
                        ResolvedType receiverType = mc.getScope().get().calculateResolvedType();
                        if (receiverType.isReferenceType()) {
                            var typeDecl = receiverType.asReferenceType().getTypeDeclaration().orElse(null);
                            if (typeDecl != null) {
                                for (var field : typeDecl.getDeclaredFields()) {
                                    if (field.getName().equals(fn)) {
                                        String fieldType = field.getType().describe();
                                        if (ClassNameValidator.isValidClassName(fieldType)) return fieldType;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            return mc.getNameAsString() + "()";
        }
        return scope.toString();
    }

    // -------------------------------------------------------------------------
    // Operator detection
    // -------------------------------------------------------------------------

    private String detectOperator(Expression expr) {
        if (isConcatExpression(expr)) return "concat";
        if (isTransformChain(expr))   return "transform";
        if (isFormatExpression(expr)) return "format";
        return "direct";
    }

    private boolean isConcatExpression(Expression expr) {
        if (expr instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) return true;
        if (expr instanceof MethodCallExpr m) {
            String name = m.getNameAsString();
            return name.equals("concat") || name.equals("join");
        }
        return false;
    }

    private boolean isFormatExpression(Expression expr) {
        if (expr instanceof MethodCallExpr m) {
            return m.getNameAsString().equals("format") || m.getNameAsString().equals("formatted");
        }
        return false;
    }

    private boolean isTransformChain(Expression expr) {
        if (!(expr instanceof MethodCallExpr m)) return false;
        String name = m.getNameAsString();
        if (name.equals("concat") || name.equals("join") || name.equals("format") || name.equals("equals")) {
            return false;
        }
        return m.getScope().filter(s -> s instanceof MethodCallExpr).isPresent();
    }

    // -------------------------------------------------------------------------
    // Field ref collection
    // -------------------------------------------------------------------------

    private void collectFieldRefs(Expression expr, List<FieldRef> refs,
                                   Map<String, Expression> aliasMap, Set<String> visited) {
        if (expr instanceof FieldAccessExpr fa) {
            handleFieldAccess(fa, refs, aliasMap, visited);

        } else if (expr instanceof NameExpr ne) {
            handleNameExpr(ne, refs, aliasMap, visited);

        } else if (expr instanceof MethodCallExpr mc) {
            handleMethodCall(mc, refs, aliasMap, visited);

        } else if (expr instanceof ArrayAccessExpr aa) {
            handleArrayAccess(aa, refs, aliasMap, visited);

        } else if (expr instanceof BinaryExpr be) {
            collectFieldRefs(be.getLeft(),  refs, aliasMap, visited);
            collectFieldRefs(be.getRight(), refs, aliasMap, visited);

        } else if (expr instanceof EnclosedExpr enc) {
            collectFieldRefs(enc.getInner(), refs, aliasMap, visited);
        }
    }

    private void handleFieldAccess(FieldAccessExpr fa, List<FieldRef> refs,
                                    Map<String, Expression> aliasMap, Set<String> visited) {
        String fieldName = fa.getNameAsString();

        // Primary: SymbolSolver for accurate FQN
        try {
            var resolvedField  = fa.resolve().asField();
            var declaringType  = resolvedField.declaringType();
            if (declaringType != null && declaringType.isReferenceType()) {
                refs.add(new FieldRef(declaringType.asReferenceType().getQualifiedName(), fieldName));
                return;
            }
        } catch (Exception ignored) {}

        // Fallback: scope resolution chain
        String className = scopeChain.resolve(ScopeContext.of(fa.getScope(), aliasMap, visited));
        refs.add(new FieldRef(className, fieldName));
    }

    private void handleNameExpr(NameExpr ne, List<FieldRef> refs,
                                 Map<String, Expression> aliasMap, Set<String> visited) {
        String varName = ne.getNameAsString();

        Expression aliased = aliasMap.get(varName);
        if (aliased != null) {
            if (visited.contains(varName)) return;
            visited.add(varName);
            collectFieldRefs(aliased, refs, aliasMap, visited);
            return;
        }

        try {
            ResolvedType type = ne.calculateResolvedType();
            if (type.isReferenceType()) return;  // static class reference, not a field
        } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {}

        refs.add(new FieldRef(null, varName));
    }

    private void handleMethodCall(MethodCallExpr mc, List<FieldRef> refs,
                                   Map<String, Expression> aliasMap, Set<String> visited) {
        // Handle traditional getter: getXxx() with no args
        if (isGetter(mc)) {
            String raw       = mc.getNameAsString();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            String className = mc.getScope()
                    .map(scope -> scopeChain.resolve(ScopeContext.of(scope, aliasMap, visited)))
                    .orElse(null);

            if (ClassNameValidator.hasValidClass(className)) {
                refs.add(new FieldRef(className, fieldName));
            }
            return;
        }

        // Handle container access methods: get(arg), poll(), pop(), next(), etc.
        if (isContainerAccessMethod(mc)) {
            // For container access, we don't create a FieldRef for the method itself,
            // but we do need to collect refs from the receiver and arguments
            mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs, aliasMap, visited));
            for (Expression arg : mc.getArguments()) {
                collectFieldRefs(arg, refs, aliasMap, visited);
            }
            return;
        }

        // Default: recurse into scope and arguments
        mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs, aliasMap, visited));
        for (Expression arg : mc.getArguments()) {
            collectFieldRefs(arg, refs, aliasMap, visited);
        }
    }

    /**
     * Handles array access expressions like arr[i] or arr[i].getField().
     * The array element type is resolved via SymbolSolver on the array expression.
     */
    private void handleArrayAccess(ArrayAccessExpr aa, List<FieldRef> refs,
                                    Map<String, Expression> aliasMap, Set<String> visited) {
        // Collect refs from the index expression (e.g., the 'i' in arr[i])
        collectFieldRefs(aa.getIndex(), refs, aliasMap, visited);

        // Try to resolve the array's component type
        try {
            ResolvedType arrayType = aa.getName().calculateResolvedType();
            if (arrayType.isArray()) {
                ResolvedType componentType = arrayType.asArrayType().getComponentType();
                if (componentType.isReference()) {
                    String componentTypeName = componentType.asReferenceType().getQualifiedName();
                    // For arr[i].getField(), the scopeChain will resolve the ArrayAccessExpr
                    // to the component type, allowing further field access resolution
                }
            }
        } catch (Exception ignored) {}

        // Recurse into the array expression itself
        collectFieldRefs(aa.getName(), refs, aliasMap, visited);
    }

    /**
     * Checks if a method call is a container access method (not a traditional getter).
     * Includes: get(arg), get() for containers, poll(), peek(), pop(), next(), etc.
     */
    private boolean isContainerAccessMethod(MethodCallExpr mc) {
        String methodName = mc.getNameAsString();

        // get() with arguments (Category A)
        if (methodName.equals("get") && !mc.getArguments().isEmpty()) {
            return true;
        }

        // get() with no args for container types (Category B)
        if (methodName.equals("get") && mc.getArguments().isEmpty() && mc.getScope().isPresent()) {
            try {
                ResolvedType receiverType = mc.getScope().get().calculateResolvedType();
                if (receiverType.isReferenceType()) {
                    String qName = receiverType.asReferenceType().getQualifiedName();
                    return qName.startsWith("java.util.Optional")
                            || qName.startsWith("java.util.concurrent.atomic.AtomicReference")
                            || qName.startsWith("java.lang.ThreadLocal");
                }
            } catch (Exception ignored) {}
        }

        // Container pop methods (Category C)
        if (isContainerPopMethod(methodName)) {
            return true;
        }

        // Map.Entry access (Category E)
        if (methodName.equals("getValue") || methodName.equals("getKey")) {
            return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Alias expansion (for operator detection pre-pass)
    // -------------------------------------------------------------------------

    private Expression expandAliases(Expression expr, Map<String, Expression> aliasMap,
                                      Set<String> visited) {
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            if (!visited.contains(varName)) {
                Expression aliased = aliasMap.get(varName);
                if (aliased != null) {
                    visited.add(varName);
                    return expandAliases(aliased, aliasMap, visited);
                }
            }
        }
        return expr;
    }

    // -------------------------------------------------------------------------
    // Builder root extraction helper
    // -------------------------------------------------------------------------

    private String extractBuilderRootClass(MethodCallExpr mc) {
        Expression current = mc;
        while (current instanceof MethodCallExpr inner) {
            if (inner.getNameAsString().equals("builder") && inner.getScope().isPresent()) {
                Expression root = inner.getScope().get();
                if (root instanceof NameExpr ne) {
                    String n = ne.getNameAsString();
                    if (!n.isEmpty() && Character.isUpperCase(n.charAt(0))) return n;
                }
            }
            current = inner.getScope().orElse(null);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Predicates
    // -------------------------------------------------------------------------

    private boolean isGetter(MethodCallExpr mc) {
        String name = mc.getNameAsString();
        return name.startsWith("get")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && mc.getArguments().isEmpty()
                && mc.getScope().isPresent();
    }

    // -------------------------------------------------------------------------
    // Normalization operation collection (GAP-03)
    // -------------------------------------------------------------------------

    private void collectNormalizationOps(Expression expr, List<String> normalizations) {
        if (expr instanceof MethodCallExpr mc) {
            String methodName = mc.getNameAsString();
            if (isNormalizationMethod(methodName)) {
                normalizations.add(buildNormalizationDescription(mc));
            }
            mc.getScope().ifPresent(scope -> collectNormalizationOps(scope, normalizations));
        } else if (expr instanceof EnclosedExpr enc) {
            collectNormalizationOps(enc.getInner(), normalizations);
        }
    }

    private boolean isNormalizationMethod(String methodName) {
        return methodName.equals("toLowerCase")  || methodName.equals("toUpperCase")
                || methodName.equals("trim")      || methodName.equals("strip")
                || methodName.equals("replace")   || methodName.equals("replaceAll")
                || methodName.equals("replaceFirst") || methodName.equals("substring")
                || methodName.equals("valueOf")   || methodName.equals("toString")
                || methodName.equals("intValue")  || methodName.equals("longValue")
                || methodName.equals("doubleValue") || methodName.equals("floatValue")
                || methodName.equals("trimToNull") || methodName.equals("trimToEmpty")
                || methodName.equals("normalize");
    }

    private String buildNormalizationDescription(MethodCallExpr mc) {
        if (!mc.getArguments().isEmpty()) {
            String args = mc.getArguments().stream()
                    .map(Expression::toString)
                    .limit(2)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return mc.getNameAsString() + "(" + args + ")";
        }
        return mc.getNameAsString() + "()";
    }
}
