package org.example.model;

import lombok.Data;

/**
 * Wrapper for a code string that supports chained transformations.
 * Enables the method-chain pattern required by PARAMETERIZED classification (§5 priority-1).
 */
@Data
public class CodeRef {

    private final String value;

    public CodeRef(String value) {
        this.value = value;
    }

    /**
     * Applies a normalization transform (e.g., uppercase + trim).
     * The presence of this chained call is the static signal for PARAMETERIZED type.
     */
    public String transform() {
        return value == null ? null : value.trim().toUpperCase();
    }
}
