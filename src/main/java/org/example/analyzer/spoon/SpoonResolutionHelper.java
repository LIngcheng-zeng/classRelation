package org.example.analyzer.spoon;

import org.example.model.ExpressionSide;
import org.example.model.FieldRef;
import org.example.util.ClassNameResolver;
import org.example.util.ClassNameResolverChain;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared resolution utilities for Spoon-based pattern extractors.
 *
 * Two resolution chains are built once at construction time:
 *
 *   exprNameChain — resolves the class name of an arbitrary CtExpression:
 *     1. CtFieldRead          → declaring type FQN
 *     2. getter CtInvocation  → delegates to getterReturnChain
 *     3. builder/monadic inv  → unwraps to contained type
 *     4. generic type arg     → first type-argument FQN
 *     5. type declaration     → getTypeDeclaration().getQualifiedName()
 *     6. qualified name       → typeRef.getQualifiedName()
 *     7. toString fallback    → expr.toString()
 *
 *   getterReturnChain — resolves the return type of a getter CtInvocation:
 *     1. executable type ref  → inv.getExecutable().getType()
 *     2. method declaration   → source-level CtMethod.getType()
 *     3. Lombok field lookup  → hierarchy search via CtModel
 *     4. invocation type      → inv.getType()
 *     5. receiver fallback    → resolveClassName(inv.getTarget())
 *
 * Stateless except for the injected CtModel.
 */
public class SpoonResolutionHelper {

    private static final Logger log = LoggerFactory.getLogger(SpoonResolutionHelper.class);

    /** Counts how many times resolveFromLombokHierarchy triggers a full model.getAllTypes() scan. */
    private final AtomicLong lombokScanCount = new AtomicLong(0);

    private final CtModel model;

    /**
     * Pre-built index: FQN → CtType.
     * Replaces O(N) model.getAllTypes() scans in resolveFromLombokHierarchy and
     * findFieldTypeInHierarchy with O(1) map lookups.
     * Built once at construction; read-only during analysis (thread-safe).
     */
    private final Map<String, CtType<?>> fqnToType;

    /** Resolves class name from any CtExpression. */
    private final ClassNameResolverChain<CtExpression<?>> exprNameChain;

    /** Resolves the return type of a getter invocation. */
    private final ClassNameResolverChain<CtInvocation<?>> getterReturnChain;

    /** Strategy chain replacing collectFieldRefs. Built after exprNameChain/getterReturnChain. */
    private final ExpressionResolverChain resolverChain;

    SpoonResolutionHelper(CtModel model) {
        this.model = model;
        // Pre-index all types by FQN for O(1) lookup — replaces repeated O(N) scans.
        Map<String, CtType<?>> index = new HashMap<>();
        if (model != null) {
            for (CtType<?> t : model.getAllTypes()) {
                index.putIfAbsent(t.getQualifiedName(), t);
            }
        }
        this.fqnToType = Collections.unmodifiableMap(index);
        // getterReturnChain must be built first; its last step calls resolveClassName()
        // which internally uses exprNameChain — safe because lambdas capture `this`.
        this.getterReturnChain = buildGetterReturnChain();
        this.exprNameChain     = buildExprNameChain();
        // resolverChain built last; safe because lambdas capture `this` lazily.
        this.resolverChain     = ExpressionResolverChain.standard(this);
    }

    public CtModel model() { return model; }

    // -------------------------------------------------------------------------
    // Public resolution entry points
    // -------------------------------------------------------------------------

    public String resolveClassName(CtExpression<?> target) {
        if (target == null) return null;
        return exprNameChain.resolve(target);
    }

    public String resolveGetterReturnType(CtInvocation<?> inv) {
        return getterReturnChain.resolve(inv);
    }

    public String resolveClassNameFromFieldRef(CtFieldRead<?> fr) {
        try {
            CtTypeReference<?> declaringType = fr.getVariable().getDeclaringType();
            if (declaringType != null) {
                try {
                    CtType<?> decl = declaringType.getTypeDeclaration();
                    if (decl != null) return decl.getQualifiedName();
                } catch (Exception ignored) {}
                return declaringType.getQualifiedName();
            }
        } catch (Exception ignored) {}
        return resolveClassName(fr.getTarget());
    }

    // -------------------------------------------------------------------------
    // Chain builders
    // -------------------------------------------------------------------------

    private ClassNameResolverChain<CtExpression<?>> buildExprNameChain() {
        return ClassNameResolverChain.of(
                // Step 1: direct field read — use declaring type
                expr -> (expr instanceof CtFieldRead<?> fr) ? resolveClassNameFromFieldRef(fr) : null,

                // Step 2: getter call — delegate to getter return type chain
                expr -> (expr instanceof CtInvocation<?> inv && isGetter(inv))
                        ? getterReturnChain.resolve(inv) : null,

                // Step 3: builder or monadic call — unwrap contained type
                expr -> {
                    if (!(expr instanceof CtInvocation<?> inv)) return null;
                    if (!isBuilderMethod(inv) && !isMonadicMethod(inv)) return null;
                    return unwrapBuilderOrMonadicType(inv);
                },

                // Step 4: generic type (e.g. List<User>) — use first type argument
                this::resolveFromGenericTypeArg,

                // Step 5: resolve via getTypeDeclaration() — best for same-package types
                this::resolveViaTypeDeclaration,

                // Step 6: plain getQualifiedName() — handles JDK and compiled types
                this::resolveViaQualifiedName,

                // Step 7: last resort — toString() representation
                CtExpression::toString
        );
    }

    private ClassNameResolverChain<CtInvocation<?>> buildGetterReturnChain() {
        return ClassNameResolverChain.of(
                // Step 1: executable's declared return type (works for compiled Lombok)
                this::resolveFromExecutableTypeRef,

                // Step 2: method declaration in source code
                this::resolveFromMethodDeclaration,

                // Step 3: Lombok fallback — find field in hierarchy via CtModel
                this::resolveFromLombokHierarchy,

                // Step 4: invocation's own type reference
                this::resolveFromInvocationType,

                // Step 5: resolve the receiver's class as a last resort
                inv -> resolveClassName(inv.getTarget())
        );
    }

    // -------------------------------------------------------------------------
    // exprNameChain step implementations
    // -------------------------------------------------------------------------

    private String resolveFromGenericTypeArg(CtExpression<?> expr) {
        try {
            CtTypeReference<?> typeRef = expr.getType();
            if (typeRef != null
                    && typeRef.getActualTypeArguments() != null
                    && !typeRef.getActualTypeArguments().isEmpty()) {
                CtTypeReference<?> typeArg = typeRef.getActualTypeArguments().get(0);
                try {
                    CtType<?> decl = typeArg.getTypeDeclaration();
                    if (decl != null) return decl.getQualifiedName();
                } catch (Exception ignored) {}
                return typeArg.getQualifiedName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveViaTypeDeclaration(CtExpression<?> expr) {
        try {
            CtTypeReference<?> typeRef = expr.getType();
            if (typeRef != null) {
                CtType<?> decl = typeRef.getTypeDeclaration();
                if (decl != null) return decl.getQualifiedName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveViaQualifiedName(CtExpression<?> expr) {
        try {
            CtTypeReference<?> typeRef = expr.getType();
            if (typeRef != null) return typeRef.getQualifiedName();
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // getterReturnChain step implementations
    // -------------------------------------------------------------------------

    private String resolveFromExecutableTypeRef(CtInvocation<?> inv) {
        try {
            CtTypeReference<?> returnType = inv.getExecutable().getType();
            if (returnType != null) return returnType.getQualifiedName();
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveFromMethodDeclaration(CtInvocation<?> inv) {
        try {
            spoon.reflect.declaration.CtExecutable<?> decl = inv.getExecutable().getDeclaration();
            if (decl instanceof CtMethod<?> m) {
                CtTypeReference<?> returnType = m.getType();
                if (returnType != null) return returnType.getQualifiedName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveFromLombokHierarchy(CtInvocation<?> inv) {
        try {
            String raw         = inv.getExecutable().getSimpleName();
            String fieldName   = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            String receiverFqn = resolveClassName(inv.getTarget());

            if (receiverFqn != null) {
                lombokScanCount.incrementAndGet();
                // O(1) lookup via pre-built index — was O(N) model.getAllTypes() scan
                CtType<?> type = fqnToType.get(receiverFqn);
                if (type != null) {
                    String fieldType = findFieldTypeInHierarchy(type, fieldName);
                    if (fieldType != null) return fieldType;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Reports accumulated Lombok lookup count — call after analysis to size the problem. */
    public long getLombokScanCount() { return lombokScanCount.get(); }

    private String resolveFromInvocationType(CtInvocation<?> inv) {
        try {
            CtTypeReference<?> type = inv.getType();
            if (type != null) return type.getQualifiedName();
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Hierarchy traversal
    // -------------------------------------------------------------------------

    public String findFieldTypeInHierarchy(CtType<?> type, String fieldName) {
        Set<String> visited = new HashSet<>();
        CtType<?>   current = type;

        while (current != null && !visited.contains(current.getQualifiedName())) {
            visited.add(current.getQualifiedName());

            for (CtField<?> field : current.getFields()) {
                if (field.getSimpleName().equals(fieldName)) {
                    try {
                        CtType<?> typeDecl = field.getType().getTypeDeclaration();
                        if (typeDecl != null) return typeDecl.getQualifiedName();
                    } catch (Exception ignored) {}
                    return field.getType().getQualifiedName();
                }
            }

            try {
                CtTypeReference<?> superRef = current.getSuperclass();
                if (superRef == null) break;
                if ("java.lang.Object".equals(superRef.getQualifiedName())) break;

                // O(1) lookup via pre-built index — was O(N) model.getAllTypes() scan
                current = fqnToType.get(superRef.getQualifiedName());
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Builder / monadic unwrapping
    // -------------------------------------------------------------------------

    public String unwrapBuilderOrMonadicType(CtInvocation<?> inv) {
        String methodName = inv.getExecutable().getSimpleName();

        // Strategy 1: build() / buildXxx() — return the build method's declared return type
        if (methodName.equals("build") || methodName.startsWith("build")) {
            try {
                CtTypeReference<?> returnType = inv.getExecutable().getType();
                if (returnType != null) return returnType.getQualifiedName();
            } catch (Exception ignored) {}
        }

        // Strategy 2: monadic map/flatMap — extract first generic type argument
        if (isMonadicMethod(inv)) {
            try {
                CtTypeReference<?> type = inv.getType();
                if (type != null && type.getActualTypeArguments() != null
                        && !type.getActualTypeArguments().isEmpty()) {
                    return type.getActualTypeArguments().get(0).getQualifiedName();
                }
            } catch (Exception ignored) {}
        }

        // Strategy 3: infer from xxx.builder() target — strip "Builder" suffix
        try {
            CtExpression<?> target = inv.getTarget();
            if (target instanceof CtInvocation<?> targetInv) {
                String targetMethod = targetInv.getExecutable().getSimpleName();
                if (targetMethod.equals("builder") || targetMethod.endsWith("Builder")) {
                    CtTypeReference<?> targetType = targetInv.getType();
                    if (targetType != null) {
                        String builderName = targetType.getSimpleName();
                        if (builderName.endsWith("Builder")) {
                            return builderName.substring(0, builderName.length() - 7);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    // -------------------------------------------------------------------------
    // Source side: expression → FieldRef list
    // -------------------------------------------------------------------------

    /**
     * Resolves an expression to its source FieldRefs using the ExecutionContext.
     * Preferred over the aliasMap-based overload for new code.
     */
    public ExpressionSide extractSourceSide(CtExpression<?> expr, ExecutionContext ctx) {
        List<FieldRef> refs = resolverChain.resolve(expr, ctx);
        return new ExpressionSide(refs, "direct");
    }

    /** @deprecated use {@link #extractSourceSide(CtExpression, ExecutionContext)} */
    @Deprecated
    public ExpressionSide extractSourceSide(CtExpression<?> expr, Map<String, CtExpression<?>> aliasMap) {
        List<FieldRef> refs = new ArrayList<>();
        collectFieldRefs(expr, refs, aliasMap, new HashSet<>());
        return new ExpressionSide(refs, "direct");
    }

    public void collectFieldRefs(CtExpression<?> expr, List<FieldRef> refs,
                          Map<String, CtExpression<?>> aliasMap, Set<String> visited) {
        if (expr instanceof CtFieldRead<?> fr) {
            refs.add(new FieldRef(resolveClassNameFromFieldRef(fr), fr.getVariable().getSimpleName()));

        } else if (expr instanceof CtVariableRead<?> vr) {
            String varName = vr.getVariable().getSimpleName();
            CtExpression<?> aliased = aliasMap.get(varName);
            if (aliased != null && !visited.contains(varName)) {
                visited.add(varName);
                collectFieldRefs(aliased, refs, aliasMap, visited);
            }

        } else if (expr instanceof CtInvocation<?> inv && isGetter(inv)) {
            String raw       = inv.getExecutable().getSimpleName();
            String fieldName = Character.toLowerCase(raw.charAt(3)) + raw.substring(4);
            refs.add(new FieldRef(resolveClassName(inv.getTarget()), fieldName));

        } else if (expr instanceof CtInvocation<?> inv && isMonadicMethod(inv)) {
            for (CtExpression<?> arg : inv.getArguments()) {
                collectFieldRefs(arg, refs, aliasMap, visited);
            }
            CtExpression<?> target = inv.getTarget();
            if (target != null) collectFieldRefs(target, refs, aliasMap, visited);

        } else if (expr instanceof spoon.reflect.code.CtLambda<?> lambda) {
            try {
                for (spoon.reflect.code.CtStatement stmt : lambda.getBody().getStatements()) {
                    if (stmt instanceof CtExpression<?> stmtExpr) {
                        collectFieldRefs(stmtExpr, refs, aliasMap, visited);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Predicates
    // -------------------------------------------------------------------------

    public boolean isSetter(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.startsWith("set")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && inv.getArguments().size() == 1
                && inv.getTarget() != null;
    }

    public boolean isGetter(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.startsWith("get")
                && name.length() > 3
                && Character.isUpperCase(name.charAt(3))
                && inv.getArguments().isEmpty()
                && inv.getTarget() != null;
    }

    public boolean isBuilderMethod(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.equals("builder")
                || name.equals("build")
                || name.endsWith("Builder")
                || (name.startsWith("with") && name.length() > 4 && Character.isUpperCase(name.charAt(4)))
                || (name.startsWith("set") && inv.getTarget() != null);
    }

    public boolean isMonadicMethod(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return name.equals("map")        || name.equals("flatMap")
                || name.equals("filter") || name.equals("orElse")
                || name.equals("orElseGet") || name.equals("orElseThrow")
                || name.equals("ifPresent") || name.equals("ifPresentOrElse");
    }

    public boolean isValidPair(ExpressionSide left, ExpressionSide right) {
        if (left.isEmpty() && right.isEmpty()) return false;
        boolean leftHasClass  = left.fields().stream().anyMatch(f -> f.className() != null);
        boolean rightHasClass = right.fields().stream().anyMatch(f -> f.className() != null);
        return leftHasClass || rightHasClass;
    }

    public boolean isSystemClass(String className) {
        if (className == null) return true;
        return className.startsWith("java.")   || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.equals("String")   || className.equals("Integer")
                || className.equals("Long")      || className.equals("Boolean")
                || className.equals("Double")    || className.equals("Float")
                || className.equals("Character") || className.equals("Byte")
                || className.equals("Short")     || className.equals("Object")
                || className.equals("List")      || className.equals("Map")
                || className.equals("Set")       || className.equals("ArrayList")
                || className.equals("HashMap")   || className.equals("HashSet")
                || className.equals("Optional")  || className.equals("Stream");
    }

    /**
     * Returns true if the type reference is, or contains, a user-defined class.
     * Handles three cases:
     *   - direct user class:            UserClass
     *   - array of user class:          UserClass[]
     *   - generic containing user class: List&lt;UserClass&gt;, Map&lt;K, UserClass&gt;
     */
    public boolean containsUserType(CtTypeReference<?> ref) {
        if (ref == null) return false;
        try {
            if (ref instanceof CtArrayTypeReference<?> atr) {
                CtTypeReference<?> component = atr.getComponentType();
                return component != null && !isSystemClass(component.getQualifiedName());
            }
            String fqn = ref.getQualifiedName();
            if (!isSystemClass(fqn)) return true;
            List<CtTypeReference<?>> args = ref.getActualTypeArguments();
            if (args != null) {
                for (CtTypeReference<?> arg : args) {
                    if (containsUserType(arg)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
