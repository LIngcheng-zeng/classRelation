package org.example.model;

public enum MappingType {
    /** A.fieldX.equals(B.fieldY) — single field to single field */
    ATOMIC,
    /** concat(A.f1, A.f2).equals(B.f3) — multi-field projection */
    COMPOSITE,
    /** Variable.of(A.fn).transform().equals(...) — intermediate variable transform */
    PARAMETERIZED,
    /** A and B share a common Map key: map keyed by A.x is looked up with B.y → A.x ≡ B.y */
    MAP_JOIN
}
