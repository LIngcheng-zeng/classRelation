package org.example.analyzer.spoon.intra;

import org.example.analyzer.spoon.ExecutionContext;
import org.example.analyzer.spoon.SpoonPatternExtractor;
import org.example.analyzer.spoon.SpoonResolutionHelper;
import org.example.model.ExpressionSide;
import org.example.model.FieldMapping;
import org.example.model.FieldRef;
import org.example.model.MappingMode;
import org.example.model.MappingType;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts field mappings from constructor invocations.
 *
 * Pattern: {@code new ClassName(arg1, arg2, ...)}
 *   Each argument that traces to a field reference is mapped to the corresponding
 *   constructor parameter (by position), using the declared parameter name when
 *   the constructor source is available, or {@code paramN} as a positional fallback.
 *
 * Example:
 *   {@code new Account(user.getPhone(), user.getId())}
 *   → User.phone → Account.fullMobile
 *   → User.id    → Account.userId
 */
public class ConstructorCallExtractor implements SpoonPatternExtractor {

    @Override
    public List<FieldMapping> extract(CtExecutable<?> method,
                                      ExecutionContext ctx,
                                      SpoonResolutionHelper helper) {
        String location = method.getSimpleName() + "(constructor-call)";
        List<FieldMapping> results = new ArrayList<>();

        for (CtConstructorCall<?> ctorCall : method.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            try {
                String sinkClass = helper.resolveClassName(ctorCall);
                if (sinkClass == null || sinkClass.equals("null")) continue;

                CtConstructor<?> ctorDecl = resolveConstructorDecl(ctorCall);
                List<CtExpression<?>> args = ctorCall.getArguments();

                if (ctorDecl != null) {
                    List<CtParameter<?>> params = ctorDecl.getParameters();
                    int limit = Math.min(params.size(), args.size());
                    for (int i = 0; i < limit; i++) {
                        emitIfValid(helper.extractSourceSide(args.get(i), ctx),
                                sinkClass, params.get(i).getSimpleName(),
                                ctorCall.toString(), location, results, helper);
                    }
                } else {
                    for (int i = 0; i < args.size(); i++) {
                        emitIfValid(helper.extractSourceSide(args.get(i), ctx),
                                sinkClass, "param" + i,
                                ctorCall.toString(), location, results, helper);
                    }
                }
            } catch (Exception ignored) {}
        }

        return results;
    }

    // -------------------------------------------------------------------------

    private CtConstructor<?> resolveConstructorDecl(CtConstructorCall<?> ctorCall) {
        try {
            spoon.reflect.declaration.CtExecutable<?> decl = ctorCall.getExecutable().getDeclaration();
            return (decl instanceof CtConstructor<?> c) ? c : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void emitIfValid(ExpressionSide sourceSide, String sinkClass, String paramName,
                              String rawExpr, String location,
                              List<FieldMapping> results, SpoonResolutionHelper helper) {
        if (sourceSide.isEmpty()) return;
        ExpressionSide sinkSide = new ExpressionSide(
                List.of(new FieldRef(sinkClass, paramName)), "constructor-param");
        if (helper.isValidPair(sourceSide, sinkSide)) {
            results.add(new FieldMapping(sourceSide, sinkSide,
                    MappingType.PARAMETERIZED, MappingMode.WRITE_ASSIGNMENT,
                    rawExpr, location));
        }
    }
}
