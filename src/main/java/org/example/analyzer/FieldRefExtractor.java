package org.example.analyzer;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.example.model.ExpressionSide;
import org.example.model.FieldRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Extracts field references from an expression.
 *
 * Recognizes patterns:
 *   - obj.field                          → direct field access
 *   - String.concat / "+" concatenation  → composite
 *   - method chains like transform()      → parameterized
 */
public class FieldRefExtractor {

    private static final Logger log = Logger.getLogger(FieldRefExtractor.class.getName());

    /**
     * Analyzes an expression and returns an ExpressionSide with all discovered FieldRefs.
     */
    public ExpressionSide extract(Expression expr) {
        return extract(expr, Collections.emptyMap());
    }

    /**
     * Analyzes an expression with a local alias map to resolve intermediate variables.
     * e.g. given aliasMap {"id" -> user.id}, NameExpr "id" expands to user.id.
     */
    public ExpressionSide extract(Expression expr, Map<String, Expression> aliasMap) {
        List<FieldRef> refs = new ArrayList<>();
        String operator = detectOperator(expr);
        collectFieldRefs(expr, refs, aliasMap);
        return new ExpressionSide(refs, operator);
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
        // "+" binary op on strings
        if (expr instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) return true;
        // String.concat(...) or similar method named concat/join/format
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
        // Detect chained method calls that are not concat/join/format — indicates transformation
        if (!(expr instanceof MethodCallExpr m)) return false;
        String name = m.getNameAsString();
        if (name.equals("concat") || name.equals("join") || name.equals("format") || name.equals("equals")) {
            return false;
        }
        // If there's a scope that is also a method call → it's a chain
        return m.getScope().filter(s -> s instanceof MethodCallExpr).isPresent();
    }

    // -------------------------------------------------------------------------
    // Recursive field ref collection
    // -------------------------------------------------------------------------

    private void collectFieldRefs(Expression expr, List<FieldRef> refs, Map<String, Expression> aliasMap) {
        if (expr instanceof FieldAccessExpr fa) {
            handleFieldAccess(fa, refs);

        } else if (expr instanceof NameExpr ne) {
            handleNameExpr(ne, refs, aliasMap);

        } else if (expr instanceof MethodCallExpr mc) {
            handleMethodCall(mc, refs, aliasMap);

        } else if (expr instanceof BinaryExpr be) {
            collectFieldRefs(be.getLeft(), refs, aliasMap);
            collectFieldRefs(be.getRight(), refs, aliasMap);

        } else if (expr instanceof EnclosedExpr enc) {
            collectFieldRefs(enc.getInner(), refs, aliasMap);
        }
        // Literals, null, etc. — ignored
    }

    private void handleFieldAccess(FieldAccessExpr fa, List<FieldRef> refs) {
        String fieldName = fa.getNameAsString();
        String className = resolveClassName(fa);
        refs.add(new FieldRef(className, fieldName));
    }

    private void handleNameExpr(NameExpr ne, List<FieldRef> refs, Map<String, Expression> aliasMap) {
        String varName = ne.getNameAsString();

        // Alias expansion: if this variable was assigned from a known expression, expand it
        Expression aliased = aliasMap.get(varName);
        if (aliased != null) {
            collectFieldRefs(aliased, refs, aliasMap);
            return;
        }

        try {
            ResolvedType type = ne.calculateResolvedType();
            if (type.isReferenceType()) {
                // Resolves to a class type reference (e.g. static class) — not a field, skip
                return;
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
            // Cannot resolve type — treat as a variable appearing in context
        }
        // Variable present in expression with no alias: record with null className.
        // Policy: appearance of a variable implies equality participation.
        refs.add(new FieldRef(null, varName));
    }

    private void handleMethodCall(MethodCallExpr mc, List<FieldRef> refs, Map<String, Expression> aliasMap) {
        // Getter pattern: obj.getXxx() → FieldRef(className_of_obj, "xxx")
        if (isGetter(mc)) {
            String raw       = mc.getNameAsString();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            String className = mc.getScope()
                    .map(this::resolveClassNameFromScope)
                    .orElse(null);
            refs.add(new FieldRef(className, fieldName));
            return;
        }
        // Recurse into scope and arguments to find field refs within the chain
        mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs, aliasMap));
        for (Expression arg : mc.getArguments()) {
            collectFieldRefs(arg, refs, aliasMap);
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

    /** Public entry point for resolving a className from an arbitrary scope expression. */
    public String resolveClassNamePublic(Expression scope) {
        return resolveClassNameFromScope(scope);
    }

    private String resolveClassNameFromScope(Expression scope) {
        // Try SymbolSolver first
        try {
            var resolvedType = scope.calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                String qualified = resolvedType.asReferenceType().getQualifiedName();
                int dot = qualified.lastIndexOf('.');
                return dot >= 0 ? qualified.substring(dot + 1) : qualified;
            }
        } catch (Exception ignored) {}
        // Fallback: use scope text heuristic
        return extractScopeName(scope);
    }

    // -------------------------------------------------------------------------
    // Class name resolution
    // -------------------------------------------------------------------------

    private String resolveClassName(FieldAccessExpr fa) {
        Expression scope = fa.getScope();
        try {
            ResolvedType resolvedType = scope.calculateResolvedType();
            if (resolvedType.isReferenceType()) {
                String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                // Return simple class name
                int dot = qualifiedName.lastIndexOf('.');
                return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
            }
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            log.fine("Cannot resolve type for: " + fa + " — " + e.getMessage());
        }

        // Fallback: use the scope text as-is (variable name or class name)
        return extractScopeName(scope);
    }

    private String extractScopeName(Expression scope) {
        if (scope instanceof NameExpr ne) {
            // Could be variable name — capitalize first letter as heuristic class name hint
            return ne.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr fa) {
            return fa.getNameAsString();
        }
        if (scope instanceof MethodCallExpr mc) {
            // e.g. getOrder().fieldName — use method name as hint
            return mc.getNameAsString() + "()";
        }
        return scope.toString();
    }
}
