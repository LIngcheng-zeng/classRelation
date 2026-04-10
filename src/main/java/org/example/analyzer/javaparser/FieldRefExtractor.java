package org.example.analyzer.javaparser;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import org.example.model.ExpressionSide;
import org.example.model.FieldRef;
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

    private static final Logger log = LoggerFactory.getLogger(FieldRefExtractor.class);

    ExpressionSide extract(Expression expr) {
        return extract(expr, Collections.emptyMap());
    }

    ExpressionSide extract(Expression expr, Map<String, Expression> aliasMap) {
        List<FieldRef> refs = new ArrayList<>();
        
        // GAP-02 Fix: First expand aliases to get the actual expression structure
        Expression expandedExpr = expandAliases(expr, aliasMap, new HashSet<>());
        
        // Then detect operator on the expanded expression
        String operator = detectOperator(expandedExpr);
        
        // Collect field refs from the expanded expression
        collectFieldRefs(expandedExpr, refs, aliasMap, new HashSet<>());
        return new ExpressionSide(refs, operator);
    }
    
    /**
     * Extracts normalization operations from an expression chain.
     * GAP-03: Captures operations like toLowerCase(), trim(), replace(), etc.
     */
    List<String> extractNormalization(Expression expr) {
        List<String> normalizations = new ArrayList<>();
        collectNormalizationOps(expr, normalizations);
        return normalizations;
    }
    
    /**
     * Expands aliases in an expression to reveal the actual expression structure.
     * GAP-02 Fix: This ensures operator detection sees the real operators (+, format, etc.)
     * instead of just variable names.
     */
    private Expression expandAliases(Expression expr, Map<String, Expression> aliasMap, Set<String> visited) {
        if (expr instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            if (!visited.contains(varName)) {
                Expression aliased = aliasMap.get(varName);
                if (aliased != null) {
                    visited.add(varName);
                    // Recursively expand in case of chained aliases
                    return expandAliases(aliased, aliasMap, visited);
                }
            }
        }
        // For non-NameExpr or unresolvable aliases, return as-is
        return expr;
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
        // Step 1: if scope is a variable name, expand via alias map first.
        // Only use the alias-expanded result if it resolves to a clean class name.
        // If the alias is a builder chain (e.g. Address.builder()...build()), expansion
        // returns "build()" which is not a class name — fall through to SymbolSolver.
        if (scope instanceof NameExpr ne) {
            String varName = ne.getNameAsString();
            if (!visited.contains(varName)) {
                Expression aliased = aliasMap.get(varName);
                if (aliased != null) {
                    visited.add(varName);
                    String resolved = resolveClassNameFromScope(aliased, aliasMap, visited);
                    if (looksLikeClassName(resolved)) return resolved;
                    // Alias did not produce a usable class name — try SymbolSolver below
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

        // Step 2.5: getter chain with receiver-type field lookup.
        // Handles a.getB().setFoo() where getB() is Lombok-generated:
        //   - resolve a's type via SymbolSolver → A
        //   - look up field "b" in A's declaration → type B
        //   - return "B"
        // Limited to one hop from a directly-resolvable receiver; deeper Lombok chains
        // are covered by the Spoon inter-procedural path.
        if (scope instanceof MethodCallExpr mc && isGetter(mc) && mc.getScope().isPresent()) {
            try {
                String raw = mc.getNameAsString();                         // "getB"
                String fn  = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);  // "b"
                ResolvedType receiverType = mc.getScope().get().calculateResolvedType();
                if (receiverType.isReferenceType()) {
                    var typeDecl = receiverType.asReferenceType().getTypeDeclaration().orElse(null);
                    if (typeDecl != null) {
                        for (var field : typeDecl.getDeclaredFields()) {
                            if (field.getName().equals(fn)) {
                                String typeName = field.getType().describe();
                                int dot = typeName.lastIndexOf('.');
                                String simple = dot >= 0 ? typeName.substring(dot + 1) : typeName;
                                if (looksLikeClassName(simple)) return simple;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Step 3: heuristic fallback from scope text
        return extractScopeName(scope);
    }

    /**
     * Returns true only when {@code name} looks like a bare Java class name:
     * non-null, non-empty, starts with uppercase, no "(" and no ".".
     */
    private boolean looksLikeClassName(String name) {
        return name != null
                && !name.isEmpty()
                && Character.isUpperCase(name.charAt(0))
                && !name.contains("(")
                && !name.contains(".");
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
            // Builder pattern: Address.builder().setX(...).build() → "Address"
            String builderClass = extractBuilderRootClass(mc);
            if (builderClass != null) return builderClass;
            return mc.getNameAsString() + "()";
        }
        return scope.toString();
    }

    /**
     * Detects the Builder pattern by walking the method-call chain.
     * If the chain ends in {@code .build()} and contains a {@code builder()} call
     * directly on a class name, returns that class name.
     *
     * Example: {@code Address.builder().city("x").build()} → {@code "Address"}
     */
    private String extractBuilderRootClass(MethodCallExpr mc) {
        // Walk inward through the scope chain looking for builder()
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
    // Normalization operation collection (GAP-03)
    // -------------------------------------------------------------------------
    
    /**
     * Collects normalization operations from method call chains.
     * Examples: toLowerCase(), trim(), replace("-", ""), String.valueOf(), etc.
     */
    private void collectNormalizationOps(Expression expr, List<String> normalizations) {
        if (expr instanceof MethodCallExpr mc) {
            String methodName = mc.getNameAsString();
            
            // Check if this is a normalization method
            if (isNormalizationMethod(methodName)) {
                // Build a readable representation of the operation
                String opDesc = buildNormalizationDescription(mc);
                normalizations.add(opDesc);
            }
            
            // Recursively check the scope (the object being called on)
            mc.getScope().ifPresent(scope -> collectNormalizationOps(scope, normalizations));
        } else if (expr instanceof EnclosedExpr enc) {
            collectNormalizationOps(enc.getInner(), normalizations);
        }
        // Other expression types don't contain normalization ops
    }
    
    /**
     * Checks if a method name represents a normalization operation.
     */
    private boolean isNormalizationMethod(String methodName) {
        return methodName.equals("toLowerCase") 
            || methodName.equals("toUpperCase")
            || methodName.equals("trim")
            || methodName.equals("strip")
            || methodName.equals("replace")
            || methodName.equals("replaceAll")
            || methodName.equals("replaceFirst")
            || methodName.equals("substring")
            || methodName.equals("valueOf")  // String.valueOf()
            || methodName.equals("toString")
            || methodName.equals("intValue")
            || methodName.equals("longValue")
            || methodName.equals("doubleValue")
            || methodName.equals("floatValue")
            || methodName.equals("trimToNull")  // Apache Commons
            || methodName.equals("trimToEmpty")  // Apache Commons
            || methodName.equals("normalize");   // Unicode normalization
    }
    
    /**
     * Builds a human-readable description of the normalization operation.
     */
    private String buildNormalizationDescription(MethodCallExpr mc) {
        String methodName = mc.getNameAsString();
        
        // For methods with arguments, include them in the description
        if (!mc.getArguments().isEmpty()) {
            String args = mc.getArguments().stream()
                    .map(arg -> arg.toString())
                    .limit(2)  // Limit to first 2 args for readability
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return methodName + "(" + args + ")";
        }
        
        return methodName + "()";
    }
}
