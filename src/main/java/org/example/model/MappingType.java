package org.example.model;

public enum MappingType {
    /** A.fieldX.equals(B.fieldY) — single field to single field */
    ATOMIC,
    /** concat(A.f1, A.f2).equals(B.f3) — multi-field projection */
    COMPOSITE,
    /** Variable.of(A.fn).transform().equals(...) — intermediate variable transform */
    PARAMETERIZED
}
