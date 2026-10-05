package com.vokyo.backend.issue;

final class PgVectors {

    private PgVectors() {
    }

    // pgvector parses a vector from text such as [0.1,0.2,0.3], exponents included.
    static String literal(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
