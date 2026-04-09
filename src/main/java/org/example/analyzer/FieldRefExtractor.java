package org.example.analyzer;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.example.model.ExpressionSide;
import org.example.model.FieldRef;

import java.util.ArrayList;
import java.util.List;
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
        List<FieldRef> refs = new ArrayList<>();
        String operator = detectOperator(expr);
        collectFieldRefs(expr, refs);
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

    private void collectFieldRefs(Expression expr, List<FieldRef> refs) {
        if (expr instanceof FieldAccessExpr fa) {
            handleFieldAccess(fa, refs);

        } else if (expr instanceof NameExpr ne) {
            // Simple name — could be a field or local variable; attempt type resolution
            handleNameExpr(ne, refs);

        } else if (expr instanceof MethodCallExpr mc) {
            handleMethodCall(mc, refs);

        } else if (expr instanceof BinaryExpr be) {
            collectFieldRefs(be.getLeft(), refs);
            collectFieldRefs(be.getRight(), refs);

        } else if (expr instanceof EnclosedExpr enc) {
            collectFieldRefs(enc.getInner(), refs);
        }
        // Literals, null, etc. — ignored
    }

    private void handleFieldAccess(FieldAccessExpr fa, List<FieldRef> refs) {
        String fieldName = fa.getNameAsString();
        String className = resolveClassName(fa);
        refs.add(new FieldRef(className, fieldName));
    }

    private void handleNameExpr(NameExpr ne, List<FieldRef> refs) {
        try {
            ResolvedType type = ne.calculateResolvedType();
            // NameExpr resolves to a type itself — not a field access we want
            // (e.g. static class reference)
        } catch (UnsolvedSymbolException | UnsupportedOperationException ignored) {
            // Cannot resolve — skip
        }
        // We do not add bare NameExpr as FieldRef since we cannot determine the owning class reliably
    }

    private void handleMethodCall(MethodCallExpr mc, List<FieldRef> refs) {
        // Recurse into scope and arguments to find field refs within the chain
        mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs));
        for (Expression arg : mc.getArguments()) {
            collectFieldRefs(arg, refs);
        }
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
