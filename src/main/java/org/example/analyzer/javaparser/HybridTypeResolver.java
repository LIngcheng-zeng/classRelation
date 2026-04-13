package org.example.analyzer.javaparser;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid type resolver combining JavaParser SymbolSolver with Spoon static analysis.
 *
 * Resolution flow:
 *   1. JavaParser SymbolSolver — most accurate when classpath is complete
 *   2. Expression-type dispatcher — handles specific AST node types:
 *        NameExpr         → Spoon variable/param lookup (cached) + alias tracing
 *        ObjectCreationExpr → classPackageIndex (O(1)) → Spoon CtClass scan
 *        CastExpr         → extract cast target type directly
 *        EnclosedExpr     → recurse on inner expression
 *        ArrayAccessExpr  → resolve array component type
 *        ConditionalExpr  → try then-branch, fallback to else-branch
 *        FieldAccessExpr  → recursive receiver resolution + Spoon CtField lookup
 *
 * Caches (all instance-level, ConcurrentHashMap):
 *   varTypeCache   — varName → FQN (CtLocalVariable/CtParameter lookup)
 *   classNameCache — simpleName → FQN (ObjectCreationExpr / CastExpr)
 *   fieldTypeCache — "FQN#field" → field type FQN (FieldAccessExpr)
 *
 * Known limitation: varTypeCache keys are variable names without method scope.
 * Cross-file same-name variables may hit a stale cache entry. Fixing this requires
 * passing method context, which is deferred.
 */
public class HybridTypeResolver {

    private static final Logger log = LoggerFactory.getLogger(HybridTypeResolver.class);

    private static final String UNRESOLVABLE = "__NR__";
    private static final int    DEFAULT_MAX_FIELD_ACCESS_DEPTH = 2;

    private final spoon.reflect.CtModel spoonModel;
    private final Map<String, String>   classPackageIndex;
    private final int                   maxFieldAccessDepth;

    /** varName → resolved type FQN (or UNRESOLVABLE) */
    private final ConcurrentHashMap<String, String> varTypeCache   = new ConcurrentHashMap<>();
    /** simpleName → FQN (or UNRESOLVABLE) */
    private final ConcurrentHashMap<String, String> classNameCache = new ConcurrentHashMap<>();
    /** "FQN#fieldName" → field type FQN (or UNRESOLVABLE) */
    private final ConcurrentHashMap<String, String> fieldTypeCache = new ConcurrentHashMap<>();

    public HybridTypeResolver(spoon.reflect.CtModel spoonModel, Map<String, String> classPackageIndex) {
        this(spoonModel, classPackageIndex, DEFAULT_MAX_FIELD_ACCESS_DEPTH);
    }

    public HybridTypeResolver(spoon.reflect.CtModel spoonModel, Map<String, String> classPackageIndex,
                               int maxFieldAccessDepth) {
        this.spoonModel          = spoonModel;
        this.classPackageIndex   = classPackageIndex != null ? classPackageIndex : Map.of();
        this.maxFieldAccessDepth = maxFieldAccessDepth;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves the type of a JavaParser expression.
     *
     * @param expr     JavaParser expression to resolve
     * @param aliasMap variable name → aliased expression (from LocalAliasResolver)
     * @return FQN type string, or null if resolution fails
     */
    public String resolveType(Expression expr, Map<String, Expression> aliasMap) {
        // Strategy 1: JavaParser SymbolSolver
        try {
            ResolvedType resolved = expr.calculateResolvedType();
            if (resolved.isReferenceType()) {
                String fqn = resolved.asReferenceType().getQualifiedName();
                log.debug("SymbolSolver resolved {} -> {}", expr, fqn);
                return fqn;
            }
        } catch (UnsolvedSymbolException e) {
            log.debug("SymbolSolver classpath miss for [{}]: {}", expr, e.getMessage());
        } catch (UnsupportedOperationException e) {
            log.debug("SymbolSolver unsupported expr type [{}]: {}", expr.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            log.trace("SymbolSolver exception for [{}]: {}", expr, e.getMessage());
        }

        // Strategy 2: expression-type dispatcher
        return dispatchByExprType(expr, aliasMap, 0);
    }

    /**
     * Resolves the component type for array access expressions.
     * For arr[i], resolves the type of arr and strips the [] suffix.
     */
    public String resolveArrayComponentType(Expression arrayExpr, Map<String, Expression> aliasMap) {
        String arrayType = resolveType(arrayExpr, aliasMap);
        if (arrayType != null && arrayType.endsWith("[]")) {
            return arrayType.substring(0, arrayType.length() - 2);
        }
        return arrayType;
    }

    /**
     * Checks if a type is a known container type (List, Map, Optional, etc.)
     */
    public boolean isContainerType(String typeName) {
        if (typeName == null) return false;
        return typeName.startsWith("java.util.List")
                || typeName.startsWith("java.util.ArrayList")
                || typeName.startsWith("java.util.LinkedList")
                || typeName.startsWith("java.util.Map")
                || typeName.startsWith("java.util.HashMap")
                || typeName.startsWith("java.util.TreeMap")
                || typeName.startsWith("java.util.LinkedHashMap")
                || typeName.startsWith("java.util.Optional")
                || typeName.startsWith("java.util.Queue")
                || typeName.startsWith("java.util.Deque")
                || typeName.startsWith("java.util.Stack")
                || typeName.startsWith("java.util.Iterator")
                || typeName.startsWith("java.util.concurrent.atomic.AtomicReference")
                || typeName.startsWith("java.lang.ThreadLocal");
    }

    /**
     * Extracts the value type from a container type string.
     * List<T> / Set<T> → T,  Map<K,V> → V,  Optional<T> → T.
     * Uses bracket-counting split to handle nested generics like Map<Map<A,B>,V>.
     */
    public String extractContainerValueType(String containerType) {
        if (containerType == null || !containerType.contains("<")) return null;
        try {
            int start = containerType.indexOf('<') + 1;
            int end   = containerType.lastIndexOf('>');
            if (start <= 0 || end <= start) return null;

            List<String> args = splitTopLevelTypeArgs(containerType.substring(start, end));
            if (args.isEmpty()) return null;

            if (containerType.startsWith("java.util.Map") && args.size() >= 2) {
                return args.get(1);
            }
            return args.get(0);
        } catch (Exception e) {
            log.trace("extractContainerValueType failed for '{}': {}", containerType, e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Dispatcher
    // -------------------------------------------------------------------------

    private String dispatchByExprType(Expression expr, Map<String, Expression> aliasMap, int depth) {
        if (expr instanceof NameExpr ne) {
            return resolveNameExpr(ne, aliasMap);
        }
        if (expr instanceof ObjectCreationExpr oce) {
            return resolveObjectCreation(oce);
        }
        if (expr instanceof CastExpr ce) {
            return resolveCast(ce);
        }
        if (expr instanceof EnclosedExpr enc) {
            // Strip parentheses and give SymbolSolver another chance via full resolveType
            return resolveType(enc.getInner(), aliasMap);
        }
        if (expr instanceof ArrayAccessExpr aa) {
            return resolveArrayComponentType(aa.getName(), aliasMap);
        }
        if (expr instanceof ConditionalExpr cond) {
            return resolveConditional(cond, aliasMap);
        }
        if (expr instanceof FieldAccessExpr fa) {
            return resolveFieldAccess(fa, aliasMap, depth);
        }
        log.debug("No dispatcher match for expr type [{}]: {}", expr.getClass().getSimpleName(), expr);
        return null;
    }

    // -------------------------------------------------------------------------
    // Per-type handlers
    // -------------------------------------------------------------------------

    private String resolveNameExpr(NameExpr ne, Map<String, Expression> aliasMap) {
        String varName = ne.getNameAsString();

        String spoonType = resolveViaSpoon(varName);
        if (spoonType != null) {
            log.debug("Spoon resolved variable '{}' -> {}", varName, spoonType);
            return spoonType;
        }

        Expression aliased = aliasMap.get(varName);
        if (aliased != null && !aliased.equals(ne)) {
            log.debug("Alias tracing: {} -> {}", varName, aliased);
            return resolveType(aliased, aliasMap);
        }

        log.debug("NameExpr '{}' unresolved: not in Spoon model or aliasMap", varName);
        return null;
    }

    private String resolveObjectCreation(ObjectCreationExpr oce) {
        String simpleName = oce.getTypeAsString();
        String fqn = resolveClassFqn(simpleName);
        log.debug("ObjectCreationExpr '{}' -> {}", simpleName, fqn);
        return fqn;
    }

    private String resolveCast(CastExpr ce) {
        String castTypeName = ce.getTypeAsString();
        String fqn = resolveClassFqn(castTypeName);
        log.debug("CastExpr '{}' -> {}", castTypeName, fqn);
        return fqn;
    }

    private String resolveConditional(ConditionalExpr cond, Map<String, Expression> aliasMap) {
        String type = resolveType(cond.getThenExpr(), aliasMap);
        if (type != null) return type;
        type = resolveType(cond.getElseExpr(), aliasMap);
        if (type != null) {
            log.debug("ConditionalExpr resolved via else-branch -> {}", type);
        }
        return type;
    }

    /**
     * Resolves FieldAccessExpr (e.g. wrapper.inner) by:
     *   1. Resolving the receiver's type (recursively, depth-limited for nested FieldAccessExpr)
     *   2. Looking up the field declaration in Spoon's model
     */
    private String resolveFieldAccess(FieldAccessExpr fa, Map<String, Expression> aliasMap, int depth) {
        if (depth >= maxFieldAccessDepth) {
            log.debug("FieldAccessExpr depth limit ({}) reached for: {}", maxFieldAccessDepth, fa);
            return null;
        }

        Expression scopeExpr = fa.getScope();
        String fieldName     = fa.getNameAsString();

        // Resolve receiver type; increment depth only for nested FieldAccessExpr chains
        String receiverType = (scopeExpr instanceof FieldAccessExpr nestedFa)
                ? resolveFieldAccess(nestedFa, aliasMap, depth + 1)
                : resolveType(scopeExpr, aliasMap);

        if (receiverType == null) {
            log.debug("FieldAccessExpr: could not resolve receiver type for '{}'", fa);
            return null;
        }

        // Strip generics from receiver type for field lookup (e.g. List<Order> → List)
        String baseReceiverType = stripGenerics(receiverType);
        String fieldType = resolveFieldTypeViaSpoon(baseReceiverType, fieldName);
        if (fieldType != null) {
            log.debug("FieldAccessExpr '{}.{}' -> {}", baseReceiverType, fieldName, fieldType);
        } else {
            log.debug("FieldAccessExpr '{}.{}': field not found in Spoon model", baseReceiverType, fieldName);
        }
        return fieldType;
    }

    // -------------------------------------------------------------------------
    // FQN resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a simple class name to its FQN.
     * Priority: classNameCache → classPackageIndex (O(1)) → Spoon CtClass scan.
     * Falls back to the simple name itself if nothing resolves.
     */
    private String resolveClassFqn(String simpleName) {
        // Strip generics if the type was written with them (e.g. "List<Order>")
        String rawName = stripGenerics(simpleName);

        String cached = classNameCache.get(rawName);
        if (cached != null) {
            return UNRESOLVABLE.equals(cached) ? rawName : cached;
        }

        // O(1) lookup in pre-built index
        String fqn = classPackageIndex.get(rawName);
        if (fqn != null) {
            classNameCache.put(rawName, fqn);
            return fqn;
        }

        // Spoon CtClass/CtInterface scan as fallback (external or non-indexed types)
        String spoonFqn = resolveClassFqnViaSpoon(rawName);
        if (spoonFqn != null) {
            classNameCache.put(rawName, spoonFqn);
            return spoonFqn;
        }

        classNameCache.put(rawName, UNRESOLVABLE);
        log.debug("resolveClassFqn: '{}' not found in index or Spoon, returning simple name", rawName);
        return rawName;
    }

    /**
     * Scans Spoon model for a CtType (class or interface) matching simpleName.
     * Returns the FQN if found, null otherwise.
     */
    private String resolveClassFqnViaSpoon(String simpleName) {
        if (spoonModel == null) return null;
        try {
            for (CtType<?> type : spoonModel.getAllTypes()) {
                if (simpleName.equals(type.getSimpleName())) {
                    String fqn = type.getQualifiedName();
                    log.debug("Spoon CtType scan: '{}' -> {}", simpleName, fqn);
                    return fqn;
                }
            }
        } catch (Exception e) {
            log.debug("Spoon CtType scan failed for '{}': {}", simpleName, e.getMessage());
        }
        return null;
    }

    /**
     * Looks up a field's declared type in Spoon's model.
     * Cache key: "receiverFQN#fieldName".
     */
    private String resolveFieldTypeViaSpoon(String receiverFqn, String fieldName) {
        if (spoonModel == null) return null;

        String cacheKey = receiverFqn + "#" + fieldName;
        String cached   = fieldTypeCache.get(cacheKey);
        if (cached != null) {
            return UNRESOLVABLE.equals(cached) ? null : cached;
        }

        try {
            for (CtElement element : spoonModel.getElements(e ->
                    e instanceof CtField<?> f
                    && fieldName.equals(f.getSimpleName())
                    && f.getDeclaringType() != null
                    && receiverFqn.equals(f.getDeclaringType().getQualifiedName()))) {

                CtTypeReference<?> typeRef = ((CtField<?>) element).getType();
                if (typeRef != null) {
                    String fieldType = extractGenericTypeString(typeRef);
                    if (fieldType != null) {
                        fieldTypeCache.put(cacheKey, fieldType);
                        return fieldType;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Spoon CtField scan failed for '{}.{}': {}", receiverFqn, fieldName, e.getMessage());
        }

        fieldTypeCache.put(cacheKey, UNRESOLVABLE);
        return null;
    }

    // -------------------------------------------------------------------------
    // Spoon variable/parameter lookup
    // -------------------------------------------------------------------------

    /**
     * Resolves a variable name via Spoon's model (CtLocalVariable + CtParameter), with caching.
     *
     * Cache contract:
     *   - hit with real value    → return immediately, skip scan
     *   - hit with UNRESOLVABLE  → already searched and failed, return null
     *   - miss                   → single combined scan, write result to cache
     *
     * Known limitation: key is varName only (no method scope). Cross-file same-name
     * variables may yield a stale result from an earlier file's lookup.
     */
    private String resolveViaSpoon(String varName) {
        if (spoonModel == null) return null;

        String cached = varTypeCache.get(varName);
        if (cached != null) {
            return UNRESOLVABLE.equals(cached) ? null : cached;
        }

        try {
            for (CtElement element : spoonModel.getElements(e ->
                    (e instanceof CtLocalVariable<?> lv && lv.getSimpleName().equals(varName))
                    || (e instanceof spoon.reflect.declaration.CtParameter<?> p && p.getSimpleName().equals(varName)))) {

                CtTypeReference<?> typeRef = (element instanceof CtLocalVariable<?> lv)
                        ? lv.getType()
                        : ((spoon.reflect.declaration.CtParameter<?>) element).getType();

                if (typeRef != null) {
                    String qualifiedName = extractGenericTypeString(typeRef);
                    if (qualifiedName != null) {
                        varTypeCache.put(varName, qualifiedName);
                        return qualifiedName;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Spoon variable scan failed for '{}': {}", varName, e.getMessage());
        }

        varTypeCache.put(varName, UNRESOLVABLE);
        return null;
    }

    // -------------------------------------------------------------------------
    // Generic type string extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts a qualified type name with generic parameters from a Spoon type reference.
     * Example: List<org.example.model.Order> instead of raw List.
     */
    private String extractGenericTypeString(CtTypeReference<?> typeRef) {
        if (typeRef == null) return null;
        try {
            String baseType = typeRef.getQualifiedName();
            List<CtTypeReference<?>> typeArgs = typeRef.getActualTypeArguments();
            if (typeArgs == null || typeArgs.isEmpty()) return baseType;

            StringBuilder sb = new StringBuilder(baseType).append("<");
            boolean first = true;
            for (CtTypeReference<?> arg : typeArgs) {
                if (!first) sb.append(",");
                String argType = extractGenericTypeString(arg);
                sb.append(argType != null ? argType : arg.getSimpleName());
                first = false;
            }
            sb.append(">");
            return sb.toString();
        } catch (Exception e) {
            log.trace("extractGenericTypeString failed: {}", e.getMessage());
            try { return typeRef.getQualifiedName(); } catch (Exception ignored) { return null; }
        }
    }

    /**
     * Strips generic parameters from a type name.
     * "java.util.List<Order>" → "java.util.List"
     */
    private String stripGenerics(String typeName) {
        if (typeName == null) return null;
        int idx = typeName.indexOf('<');
        return idx > 0 ? typeName.substring(0, idx) : typeName;
    }

    /**
     * Splits a generic type argument string by top-level commas only.
     * Handles nested generics via bracket-depth tracking.
     * "K,Map<A,B>,V" → ["K", "Map<A,B>", "V"]
     */
    private List<String> splitTopLevelTypeArgs(String typeArgs) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < typeArgs.length(); i++) {
            char c = typeArgs.charAt(i);
            if      (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(typeArgs.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < typeArgs.length()) {
            result.add(typeArgs.substring(start).trim());
        }
        return result;
    }
}
