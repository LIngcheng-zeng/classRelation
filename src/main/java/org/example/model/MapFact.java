package org.example.model;

/**
 * Models a Map variable whose key and value have known field origins.
 *
 * Example:
 *   Map<String, String> nameMapProduct =
 *       list.stream().collect(Collectors.toMap(Enterprise::getName, Enterprise::getProduct));
 *
 *   → MapFact(
 *       variableName   = "nameMapProduct",
 *       keyProvenance  = FieldProvenance("Enterprise", "name"),
 *       valueProvenance = FieldProvenance("Enterprise", "product"),
 *       sourceExpression = "Collectors.toMap(Enterprise::getName, Enterprise::getProduct)"
 *     )
 *
 * When a second Map is looked up with a key that has provenance matching this Map's
 * keyProvenance, the two key fields are implicitly equal (MAP_JOIN relationship).
 */
public record MapFact(
        String variableName,
        FieldProvenance keyProvenance,
        FieldProvenance valueProvenance,
        String sourceExpression
) {

    public static MapFact of(String variableName,
                             FieldProvenance keyProvenance,
                             FieldProvenance valueProvenance,
                             String sourceExpression) {
        return new MapFact(variableName, keyProvenance, valueProvenance, sourceExpression);
    }
}
