package org.example.analyzer.spoon;

import org.example.model.FieldMapping;
import spoon.reflect.declaration.CtExecutable;

import java.util.List;

/**
 * Single-responsibility extractor for one field-mapping pattern inside a Spoon AST.
 *
 * Each implementation is focused on exactly one syntactic pattern
 * (composition, builder chain, constructor args, direct setter, inter-procedural projection).
 * All share utility methods via {@link SpoonResolutionHelper}.
 *
 * Implementations must be stateless — all results are returned, never accumulated.
 */
public interface SpoonPatternExtractor {

    /**
     * @param method   the executable whose body is being inspected
     * @param ctx      execution context for {@code method} (built via ExecutionContext.forMethod)
     * @param helper   shared resolution utilities
     * @return discovered FieldMappings; never null, may be empty
     */
    List<FieldMapping> extract(CtExecutable<?> method,
                               ExecutionContext ctx,
                               SpoonResolutionHelper helper);
}
