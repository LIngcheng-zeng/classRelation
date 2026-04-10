package org.example.analyzer.javaparser;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.example.model.ExpressionSide;
import org.example.model.FieldRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Extracts field references from a JavaParser expression.
 *
 * Recognizes patterns:
 *   - obj.field                          → direct field access
 *   - String.concat / "+" concatenation  → composite
 *   - method chains like transform()      → parameterized
 *
 * Fixes vs. previous version:
 *   1. Cycle detection: alias expansion uses a visited-set to prevent
 *      StackOverflowError on circular alias maps (e.g. a=b; b=a).
 *   2. Alias map in scope resolution: resolveClassNameFromScope now checks
 *      the alias map before falling back to SymbolSolver, so variables
 *      aliased to typed expressions resolve to the correct class name.
 *   3. Capitalization fallback: when SymbolSolver fails and the scope is a
 *      bare NameExpr (variable name), the first letter is capitalized as a
 *      heuristic class name hint (e.g. "order" → "Order").
 */
class FieldRefExtractor {

    private static final Logger log = Logger.getLogger(FieldRefExtractor.class.getName());

    ExpressionSide extract(Expression expr) {
        return extract(expr, Collections.emptyMap());
    }

    ExpressionSide extract(Expression expr, Map<String, Expression> aliasMap) {
        List<FieldRef> refs = new ArrayList<>();
        String operator = detectOperator(expr);
        collectFieldRefs(expr, refs, aliasMap, new HashSet<>());
        return new ExpressionSide(refs, operator);
    }

    /**
     * Resolves the declaring class name from a scope expression.
     * Public entry point used by SetterMappingExtractor.
     */
    String resolveClassNamePublic(Expression scope, Map<String, Expression> aliasMap) {
        return resolveClassNameFromScope(scope, aliasMap, new HashSet<>());
    }

    // -------------------------------------------------------------------------
    // Operator detection
    // -------------------------------------------------------------------------

    private String detectOperator(Expression expr) {
        if (isConcatExpression(expr)) return "concat";
        if (isTransformChain(expr)) return "transform";
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
    // Field ref collection — all paths carry visited to prevent cycles
    // -------------------------------------------------------------------------

    private void collectFieldRefs(Expression expr, List<FieldRef> refs,
                                   Map<String, Expression> aliasMap, Set<String> visited) {
        if (expr instanceof FieldAccessExpr fa) {
            handleFieldAccess(fa, refs, aliasMap, visited);

        } else if (expr instanceof NameExpr ne) {
            handleNameExpr(ne, refs, aliasMap, visited);

        } else if (expr instanceof MethodCallExpr mc) {
            handleMethodCall(mc, refs, aliasMap, visited);

        } else if (expr instanceof BinaryExpr be) {
            collectFieldRefs(be.getLeft(),  refs, aliasMap, visited);
            collectFieldRefs(be.getRight(), refs, aliasMap, visited);

        } else if (expr instanceof EnclosedExpr enc) {
            collectFieldRefs(enc.getInner(), refs, aliasMap, visited);
        }
        // Literals, null, casts, etc. — ignored
    }

    private void handleFieldAccess(FieldAccessExpr fa, List<FieldRef> refs,
                                    Map<String, Expression> aliasMap, Set<String> visited) {
        String fieldName = fa.getNameAsString();
        
        // Primary path: use fa.resolve() for accurate type resolution (covers non-Lombok chains)
        try {
            com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration resolvedField = fa.resolve().asField();
            com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration declaringType = resolvedField.declaringType();
            if (declaringType != null && declaringType.isReferenceType()) {
                String qualifiedName = declaringType.asReferenceType().getQualifiedName();
                int dot = qualifiedName.lastIndexOf('.');
                String className = dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
                refs.add(new FieldRef(className, fieldName));
                return;
            }
        } catch (Exception ignored) {
            // Fallback to original scope-based resolution
        }
        
        // Fallback: resolve from scope expression
        String className = resolveClassNameFromScope(fa.getScope(), aliasMap, visited);
        refs.add(new FieldRef(className, fieldName));
    }

    private void handleNameExpr(NameExpr ne, List<FieldRef> refs,
                                 Map<String, Expression> aliasMap, Set<String> visited) {
        String varName = ne.getNameAsString();

        // Alias expansion with cycle guard
        Expression aliased = aliasMap.get(varName);
        if (aliased != null) {
            if (visited.contains(varName)) return;
            visited.add(varName);
            collectFieldRefs(aliased, refs, aliasMap, visited);
            return;
        }

        try {
            ResolvedType type = ne.calculateResolvedType();
            if (type.isReferenceType()) {
                // Static class reference, not a field — skip
                return;
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
            // Cannot resolve type — treat as a variable appearing in context
        }
        refs.add(new FieldRef(null, varName));
    }

    private void handleMethodCall(MethodCallExpr mc, List<FieldRef> refs,
                                   Map<String, Expression> aliasMap, Set<String> visited) {
        if (isGetter(mc)) {
            String raw       = mc.getNameAsString();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            String className = mc.getScope()
                    .map(scope -> resolveClassNameFromScope(scope, aliasMap, visited))
                    .orElse(null);
            refs.add(new FieldRef(className, fieldName));
            return;
        }
        mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs, aliasMap, visited));
        for (Expression arg : mc.getArguments()) {
            collectFieldRefs(arg, refs, aliasMap, visited);
        }
    }

    private boolean isGetter(MethodCallExpr mc) {
        String name = mc.getNameAsString();
        return name.startsWith("get")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && mc.getArguments().isEmpty()
                && mc.getScope().isPresent();
    }

    // -------------------------------------------------------------------------
    // Class name resolution
    // -------------------------------------------------------------------------

    private String resolveClassNameFromScope(Expression scope,
                                              Map<String, Expression> aliasMap,
                                              Set<String> visited) {
        // Step 1: if scope is a variable name, expand via alias map first
        if (scope instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            if (!visited.contains(varName)) {
                Expression aliased = aliasMap.get(varName);
                if (aliased != null) {
                    visited.add(varName);
                    String resolved = resolveClassNameFromScope(aliased, aliasMap, visited);
                    if (resolved != null) return resolved;
                }
            }
        }

        // Step 2: try SymbolSolver for precise type resolution
        try {
            ResolvedType resolvedType = scope.calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                String qualified = resolvedType.asReferenceType().getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception ignored) {}

        // Step 3: heuristic fallback from scope text
        return extractScopeName(scope);
    }

    private String extractScopeName(Expression scope) {
        if (scope instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            if (name.isEmpty()) return name;
            // Capitalize first letter: variable 'order' → class hint 'Order'
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        if (scope instanceof ObjectCreationExpr oce) {
            return oce.getTypeAsString();
        }
        if (scope instanceof FieldAccessExpr fa) {
            return fa.getNameAsString();
        }
        if (scope instanceof MethodCallExpr mc) {
            return mc.getNameAsString() + "()";
        }
        return scope.toString();
    }
}
