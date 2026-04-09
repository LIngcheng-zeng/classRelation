package org.example.model;

public enum MappingMode {
    /** equals()-based read predicate: caller.equals(arg) */
    READ_PREDICATE,
    /** Assignment-based write: target.field = source.field */
    WRITE_ASSIGNMENT
}
