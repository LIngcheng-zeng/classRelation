package org.example.analyzer.javaparser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

import java.util.Map;
import java.util.Optional;

/**
 * Hybrid type resolver that combines Spoon's static analysis with JavaParser's symbol solving.
 *
 * Strategy:
 *   1. Try JavaParser SymbolSolver first (most accurate when classpath is complete)
 *   2. Fallback to Spoon's type inference (works in noClasspath mode, preserves generics)
 *   3. Use alias map to trace variable declarations and extract generic type info
 *
 * This is particularly useful for resolving container types like List<T>, Map<K,V>
 * where JavaParser alone may fail to infer generic parameters from local variables.
 */
public class HybridTypeResolver {

    private static final Logger log = LoggerFactory.getLogger(HybridTypeResolver.class);

    private final spoon.reflect.CtModel spoonModel;

    public HybridTypeResolver(spoon.reflect.CtModel spoonModel) {
        this.spoonModel = spoonModel;
    }

    /**
     * Resolves the type of a JavaParser expression using hybrid strategy.
     *
     * @param expr     JavaParser expression to resolve
     * @param aliasMap variable name → JavaParser expression mapping
     * @return qualified type name, or null if resolution fails
     */
    public String resolveType(Expression expr, Map<String, Expression> aliasMap) {
        // Strategy 1: JavaParser SymbolSolver
        try {
            ResolvedType resolvedType = expr.calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                String fqn = resolvedType.asReferenceType().getQualifiedName();
                log.debug("JavaParser resolved {} -> {}", expr, fqn);
                return fqn;
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            log.debug("JavaParser failed for {}: {}", expr, e.getMessage());
        } catch (Exception e) {
            log.trace("JavaParser exception for {}: {}", expr, e.getMessage());
        }

        // Strategy 2: Spoon-based resolution for NameExpr (local variables)
        if (expr instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            String spoonType = resolveViaSpoon(varName, aliasMap);
            if (spoonType != null) {
                log.debug("Spoon resolved variable '{}' -> {}", varName, spoonType);
                return spoonType;
            }
        }

        // Strategy 3: Trace through alias map and retry
        if (expr instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            Expression aliased = aliasMap.get(varName);
            if (aliased != null && !aliased.equals(expr)) {
                log.debug("Tracing alias: {} -> {}", varName, aliased);
                return resolveType(aliased, aliasMap);
            }
        }

        log.debug("All strategies failed for expression: {}", expr);
        return null;
    }

    /**
     * Resolves variable type using Spoon's AST analysis.
     * Looks up the variable in Spoon's model and extracts its declared type with generics.
     */
    private String resolveViaSpoon(String varName, Map<String, Expression> aliasMap) {
        if (spoonModel == null) {
            return null;
        }

        try {
            // Search for variable declarations in Spoon model
            for (CtElement element : spoonModel.getElements(e ->
                    e instanceof CtLocalVariable<?> &&
                    ((CtLocalVariable<?>) e).getSimpleName().equals(varName))) {

                CtLocalVariable<?> localVar = (CtLocalVariable<?>) element;
                CtTypeReference<?> typeRef = localVar.getType();

                if (typeRef != null) {
                    // Extract full generic type information
                    String qualifiedName = extractGenericTypeString(typeRef);
                    if (qualifiedName != null) {
                        return qualifiedName;
                    }
                }
            }

            // Also check method/lambda parameters
            for (CtElement element : spoonModel.getElements(e ->
                    e instanceof spoon.reflect.declaration.CtParameter<?> &&
                    ((spoon.reflect.declaration.CtParameter<?>) e).getSimpleName().equals(varName))) {

                spoon.reflect.declaration.CtParameter<?> param =
                        (spoon.reflect.declaration.CtParameter<?>) element;
                CtTypeReference<?> typeRef = param.getType();

                if (typeRef != null) {
                    String qualifiedName = extractGenericTypeString(typeRef);
                    if (qualifiedName != null) {
                        return qualifiedName;
                    }
                }
            }

        } catch (Exception e) {
            log.trace("Spoon resolution failed for variable '{}': {}", varName, e.getMessage());
        }

        return null;
    }

    /**
     * Extracts qualified type name with generic parameters from Spoon type reference.
     * Example: List<org.example.model.GetModel1> instead of just List
     */
    private String extractGenericTypeString(CtTypeReference<?> typeRef) {
        if (typeRef == null) {
            return null;
        }

        try {
            String baseType = typeRef.getQualifiedName();

            // If it has actual type arguments, include them
            if (typeRef.getActualTypeArguments() != null && !typeRef.getActualTypeArguments().isEmpty()) {
                StringBuilder sb = new StringBuilder(baseType);
                sb.append("<");

                boolean first = true;
                for (CtTypeReference<?> arg : typeRef.getActualTypeArguments()) {
                    if (!first) {
                        sb.append(",");
                    }
                    String argType = extractGenericTypeString(arg);
                    if (argType != null) {
                        sb.append(argType);
                    } else {
                        sb.append(arg.getSimpleName());
                    }
                    first = false;
                }

                sb.append(">");
                return sb.toString();
            }

            return baseType;

        } catch (Exception e) {
            log.trace("Failed to extract generic type string: {}", e.getMessage());
            return typeRef.getQualifiedName();
        }
    }

    /**
     * Resolves the component type for array access expressions.
     * For arr[i], resolves the type of arr and returns its component type.
     */
    public String resolveArrayComponentType(Expression arrayExpr, Map<String, Expression> aliasMap) {
        String arrayType = resolveType(arrayExpr, aliasMap);
        if (arrayType != null && arrayType.endsWith("[]")) {
            // Remove [] suffix to get component type
            return arrayType.substring(0, arrayType.length() - 2);
        }
        return arrayType;
    }

    /**
     * Checks if a type is a known container type (List, Map, Optional, etc.)
     */
    public boolean isContainerType(String typeName) {
        if (typeName == null) {
            return false;
        }

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
     * Extracts the value type from a container type.
     * For List<T> or Set<T>, returns T
     * For Map<K,V>, returns V
     * For Optional<T>, returns T
     */
    public String extractContainerValueType(String containerType) {
        if (containerType == null || !containerType.contains("<")) {
            return null;
        }

        try {
            // Extract content between < and >
            int start = containerType.indexOf('<') + 1;
            int end = containerType.lastIndexOf('>');

            if (start <= 0 || end <= start) {
                return null;
            }

            String typeArgs = containerType.substring(start, end);

            // For Map<K,V>, we want V (the second type argument)
            if (containerType.startsWith("java.util.Map")) {
                String[] args = typeArgs.split(",", 2);
                if (args.length == 2) {
                    return args[1].trim();
                }
            }

            // For List<T>, Optional<T>, etc., we want the first (and usually only) type argument
            String[] args = typeArgs.split(",");
            if (args.length > 0) {
                return args[0].trim();
            }

        } catch (Exception e) {
            log.trace("Failed to extract container value type from '{}': {}", containerType, e.getMessage());
        }

        return null;
    }
}
