package org.example.classifier;

import org.example.model.ExpressionSide;
import org.example.model.MappingType;

/**
 * Classifies a pair of ExpressionSides into one of the three mapping types.
 */
public class RelationshipClassifier {

    public MappingType classify(ExpressionSide left, ExpressionSide right) {
        // GAP-01 Fix: If either side has a field with className=null (unresolved variable),
        // classify as PARAMETERIZED since it depends on external context
        boolean leftHasNullClass = left.fields().stream().anyMatch(f -> f.className() == null);
        boolean rightHasNullClass = right.fields().stream().anyMatch(f -> f.className() == null);
        
        if (leftHasNullClass || rightHasNullClass) {
            return MappingType.PARAMETERIZED;
        }
        
        boolean leftTransform  = "transform".equals(left.operatorDesc());
        boolean rightTransform = "transform".equals(right.operatorDesc());

        if (leftTransform || rightTransform) {
            return MappingType.PARAMETERIZED;
        }

        boolean leftComposite  = isComposite(left);
        boolean rightComposite = isComposite(right);

        if (leftComposite || rightComposite) {
            return MappingType.COMPOSITE;
        }

        return MappingType.ATOMIC;
    }

    private boolean isComposite(ExpressionSide side) {
        if (side.fields().size() > 1) return true;
        return "concat".equals(side.operatorDesc()) || "format".equals(side.operatorDesc());
    }
}
