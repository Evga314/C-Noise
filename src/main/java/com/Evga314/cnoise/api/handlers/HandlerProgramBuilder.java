package com.Evga314.cnoise.api.handlers;

/**
 * Restricted instruction builder exposed to density-node handlers.
 *
 * <p>Every handler compilation must leave exactly one value on the stack.
 * Child compilation and Java fallback calls also leave exactly one value.
 * The compiler owns program termination and bytecode verification.
 *
 * <p>Implementations must reject invalid stack operations and enforce their
 * configured instruction and constant limits.
 */
public interface HandlerProgramBuilder {

    /** Coordinates that can be pushed onto the evaluation stack. */
    enum Coordinate {
        X,
        Y,
        Z
    }

    /** Pushes a numeric constant. */
    void pushConstant(double value);

    /** Pushes one of the current evaluation coordinates. */
    void pushCoordinate(Coordinate coordinate);

    /** Replaces the top two values with their sum. */
    void add();

    /** Replaces the top two values with the first value minus the second. */
    void subtract();

    /** Replaces the top two values with their product. */
    void multiply();

    /** Replaces the top two values with their quotient. */
    void divide();

    /** Replaces the top two values with their minimum. */
    void min();

    /** Replaces the top two values with their maximum. */
    void max();

    /** Replaces the top value with its negation. */
    void negate();

    /** Replaces the top value with its absolute value. */
    void absolute();

    /** Replaces the top three values with the value clamped to the supplied bounds. */
    void clamp();

    /**
     * Emits a Java fallback for the supplied original node.
     *
     * <p>The compiler retains the callback and its required context. The
     * implementation must not expose native memory or raw native addresses.
     * The fallback produces one stack value.
     *
     * @param originalNode original Minecraft node evaluated by Java
     */
    void callJava(Object originalNode);
}
