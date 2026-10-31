package com.Evga314.cnoise.api.descriptors;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable description of a density node and its execution semantics.
 *
 * @param typeId stable namespaced node type identifier
 * @param parameters immutable, stable textual parameter representations
 * @param childNodeIds identifiers of the node's children in the compiled graph
 * @param purity purity and side-effect classification
 * @param cacheScope lifetime and scope of cached values
 * @param coordinateDependency coordinates required by the node
 * @param contextRequirements additional execution context required by the node
 * @param estimatedCost relative estimated evaluation cost, used for diagnostics
 * @param semanticVersion version of the node's mathematical semantics
 */
public record NodeDescriptor(
        String typeId,
        Map<String, String> parameters,
        List<Integer> childNodeIds,
        Purity purity,
        CacheScope cacheScope,
        CoordinateDependency coordinateDependency,
        Set<ContextRequirement> contextRequirements,
        double estimatedCost,
        String semanticVersion) {

    private static final Pattern TYPE_ID_PATTERN =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");

    public NodeDescriptor {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(childNodeIds, "childNodeIds");
        Objects.requireNonNull(purity, "purity");
        Objects.requireNonNull(cacheScope, "cacheScope");
        Objects.requireNonNull(coordinateDependency, "coordinateDependency");
        Objects.requireNonNull(contextRequirements, "contextRequirements");
        Objects.requireNonNull(semanticVersion, "semanticVersion");

        if (!TYPE_ID_PATTERN.matcher(typeId).matches()) {
            throw new IllegalArgumentException(
                    "Node type ID must use the namespace:path format: " + typeId);
        }
        if (!VERSION_PATTERN.matcher(semanticVersion).matches()) {
            throw new IllegalArgumentException(
                    "Invalid semantic version: " + semanticVersion);
        }
        if (!Double.isFinite(estimatedCost) || estimatedCost < 0.0) {
            throw new IllegalArgumentException(
                    "Estimated cost must be finite and non-negative: " + estimatedCost);
        }

        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (parameter.getKey() == null || parameter.getKey().isBlank()) {
                throw new IllegalArgumentException("Parameter names must not be blank");
            }
            Objects.requireNonNull(parameter.getValue(),
                    "Parameter value for " + parameter.getKey());
        }
        parameters = Map.copyOf(parameters);

        for (Integer childNodeId : childNodeIds) {
            if (childNodeId == null || childNodeId < 0) {
                throw new IllegalArgumentException(
                        "Child node IDs must be non-negative: " + childNodeId);
            }
        }
        childNodeIds = List.copyOf(childNodeIds);
        contextRequirements = Set.copyOf(contextRequirements);
    }

    /** Whether evaluation is free of side effects and independent of mutable state. */
    public enum Purity {
        PURE,
        CONTEXT_DEPENDENT,
        IMPURE
    }

    /** Scope in which a computed value may be reused. */
    public enum CacheScope {
        NONE,
        POINT,
        COLUMN,
        CELL,
        SHARED_IMMUTABLE
    }

    /** Coordinates that can affect the node's result. */
    public enum CoordinateDependency {
        NONE,
        X,
        Y,
        Z,
        XY,
        XZ,
        YZ,
        XYZ
    }

    /** Additional inputs required beyond the point coordinates. */
    public enum ContextRequirement {
        CELL,
        SAMPLER,
        WORLD,
        NOISE_SETTINGS,
        BLENDER,
        RANDOM_STATE
    }
}
