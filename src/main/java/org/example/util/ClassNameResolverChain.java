package org.example.util;

import java.util.List;

/**
 * Composite resolver that tries each {@link ClassNameResolver} in order
 * and returns the first non-null result.
 *
 * Usage:
 * <pre>
 *   ClassNameResolverChain&lt;MyCtx&gt; chain = ClassNameResolverChain.of(
 *       ctx -> step1(ctx),
 *       ctx -> step2(ctx),
 *       ctx -> "fallback"
 *   );
 *   String name = chain.resolve(context);
 * </pre>
 */
public final class ClassNameResolverChain<C> {

    private final List<ClassNameResolver<C>> resolvers;

    @SafeVarargs
    public static <C> ClassNameResolverChain<C> of(ClassNameResolver<C>... resolvers) {
        return new ClassNameResolverChain<>(List.of(resolvers));
    }

    private ClassNameResolverChain(List<ClassNameResolver<C>> resolvers) {
        this.resolvers = resolvers;
    }

    /**
     * Iterates the chain and returns the first non-null result, or {@code null}
     * if no resolver handled the context.
     */
    public String resolve(C context) {
        for (ClassNameResolver<C> r : resolvers) {
            String name = r.resolve(context);
            if (name != null) return name;
        }
        return null;
    }
}
