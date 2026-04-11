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
 *
 * Scope resolution (class name from an expression) is handled by a
 * {@link ClassNameResolverChain} built once at construction time:
 *
 *   1. AliasExpansionResolver     — expands NameExpr via alias map, recurses
 *   2. SymbolSolverResolver       — JavaParser SymbolSolver (calculateResolvedType)
 *   3. GetterChainResolver        — recursive resolution for getter chains a.getB().getC()
 *   4. HeuristicScopeResolver     — builder root, getter SymbolSolver, capitalization fallback
 */
public class FieldRefExtractor {

    private static final Logger log = LoggerFactory.getLogger(FieldRefExtractor.class);

    /** Resolution chain built once; all steps are stateless method references on {@code this}. */
    private final ClassNameResolverChain<ScopeContext> scopeChain = ClassNameResolverChain.of(
            this::resolveViaAliasExpansion,
            this::resolveViaSymbolSolver,
            this::resolveViaGetterChain,
            this::resolveViaHeuristic
    );

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
     * Step 3 — handle getter chains like {@code a.getB().getC()} where SymbolSolver
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
        mc.getScope().ifPresent(scope -> collectFieldRefs(scope, refs, aliasMap, visited));
        for (Expression arg : mc.getArguments()) {
            collectFieldRefs(arg, refs, aliasMap, visited);
        }
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
