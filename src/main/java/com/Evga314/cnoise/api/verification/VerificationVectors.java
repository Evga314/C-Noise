package com.Evga314.cnoise.api.verification;

import java.util.List;
import java.util.Objects;

/**
 * Immutable deterministic test vectors supplied by a density-node handler.
 *
 * Each vector describes one coordinate input and the expected result. Expected
 * values are stored as raw IEEE-754 bits so verification can distinguish
 * signed zero and preserve the exact expected floating-point representation.
 *
 * @param vectors ordered verification cases
 */
public record VerificationVectors(List<Vector> vectors) {
    public static final int MAX_VECTORS = 4_096;

    public VerificationVectors {
        Objects.requireNonNull(vectors, "vectors");
        if (vectors.isEmpty() || vectors.size() > MAX_VECTORS) {
            throw new IllegalArgumentException(
                    "Vector count must be between 1 and " + MAX_VECTORS);
        }
        for (Vector vector : vectors) {
            Objects.requireNonNull(vector, "verification vector");
        }
        vectors = List.copyOf(vectors);
    }

    /**
     * One deterministic evaluation case.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param expectedRawBits expected result as raw IEEE-754 bits
     */
    public record Vector(double x, double y, double z, long expectedRawBits) {

        /** Creates a vector from an ordinary expected double value. */
        public static Vector of(double x, double y, double z, double expected) {
            return new Vector(x, y, z, Double.doubleToRawLongBits(expected));
        }

        /** Returns the expected result represented by {@link #expectedRawBits()}. */
        public double expectedValue() {
            return Double.longBitsToDouble(expectedRawBits);
        }

        /** Checks exact IEEE-754 representation equality. */
        public boolean matchesExactly(double actual) {
            return Double.doubleToRawLongBits(actual) == expectedRawBits;
        }
    }
}
