package com.Evga314.cnoise.api.handlers;

import com.Evga314.cnoise.api.descriptors.NodeDescriptor;

/**
 * Read-only compilation services made available to density-node handlers.
 *
 * <p>The context is valid only for the duration of one compilation operation.
 * Handlers must not retain it or use it from another thread.
 */
public interface HandlerCompilationContext {

    /** Returns the Minecraft release for which the current program is compiled. */
    String minecraftVersion();

    /**
     * Returns whether the current compilation environment provides a requirement.
     *
     * @param requirement required context capability
     * @return true when the capability is available
     */
    boolean hasContext(NodeDescriptor.ContextRequirement requirement);

    /**
     * Recursively compiles a child node using the active handler registry.
     *
     * <p>The child result is emitted into the supplied builder and leaves exactly
     * one value on its evaluation stack. Implementations are responsible for
     * identity tracking, cycle detection, handler selection and fallback policy.
     *
     * @param child original child node
     * @param builder active program builder
     */
    void compileChild(Object child, HandlerProgramBuilder builder);
}
