package com.Evga314.cnoise.api.handlers;

import com.Evga314.cnoise.api.descriptors.NodeDescriptor;
import com.Evga314.cnoise.api.verification.VerificationVectors;

/**
 * Public SPI implemented by modules that support Minecraft density nodes.
 *
 * <p>A handler describes and compiles one node type. It must preserve the
 * mathematical behavior of the corresponding Java implementation. Unknown,
 * incompatible or unsupported behavior must remain on the Java fallback path.
 *
 * @param <T> concrete node type supported by this handler
 */
public interface DensityNodeHandler<T> {

    /** Returns the concrete Java type handled by this implementation. */
    Class<T> nodeType();

    /**
     * Checks whether an object can be handled by this implementation.
     *
     * <p>The default implementation uses {@link #nodeType()}. Registries must
     * still resolve overlapping handlers deterministically.
     */
    default boolean supports(Object node) {
        return node != null && nodeType().isInstance(node);
    }

    /** Describes the node's parameters, children and execution semantics. */
    NodeDescriptor describe(T node);

    /**
     * Emits the node's computation into the active program builder.
     *
     * <p>On successful return, exactly one result value must have been emitted.
     * Child nodes should be compiled through the supplied context rather than
     * independently reimplemented or evaluated during compilation.
     *
     * @param node original Minecraft node
     * @param context read-only services for the current compilation
     * @param builder restricted instruction builder
     */
    void compile(T node, HandlerCompilationContext context, HandlerProgramBuilder builder);

    /** Returns deterministic test vectors used to verify this handler. */
    VerificationVectors verificationVectors();

    /** Returns handler identity, API compatibility and semantic version. */
    HandlerVersion version();
}
