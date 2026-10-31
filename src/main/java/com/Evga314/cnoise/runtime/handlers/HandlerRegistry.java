package com.Evga314.cnoise.runtime.handlers;

import com.Evga314.cnoise.api.handlers.DensityNodeHandler;
import com.Evga314.cnoise.api.handlers.HandlerVersion;
import com.Evga314.cnoise.api.verification.VerificationVectors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime registry for built-in and externally supplied density-node handlers.
 *
 * <p>Registration is performed before compilation. A registry can be frozen
 * after initialization to prevent its handler set from changing while programs
 * are being compiled. Duplicate handler IDs and duplicate node classes are
 * rejected. When multiple assignable handlers match a node, the closest type
 * in the Java inheritance graph wins. Equally specific matches are rejected
 * rather than resolved according to registration order.
 */
public final class HandlerRegistry {
    private final String minecraftVersion;
    private final Map<String, RegisteredHandler<?>> handlersById = new LinkedHashMap<>();
    private final Map<Class<?>, RegisteredHandler<?>> handlersByType = new HashMap<>();
    private boolean frozen;

    /**
     * Creates a registry for one Minecraft release.
     *
     * @param minecraftVersion target numeric dotted Minecraft version
     */
    public HandlerRegistry(String minecraftVersion) {
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("Minecraft version must not be blank");
        }
    }

    /**
     * Registers and validates a handler.
     *
     * <p>The handler's version and verification vectors are captured at
     * registration time. A handler must support the target Minecraft release
     * and the current public handler API.
     *
     * @param handler handler to register
     */
    public synchronized void register(DensityNodeHandler<?> handler) {
        requireMutable();
        Objects.requireNonNull(handler, "handler");

        Class<?> nodeType = Objects.requireNonNull(handler.nodeType(), "handler.nodeType()");
        if (nodeType.isPrimitive() || nodeType.isArray()) {
            throw new IllegalArgumentException(
                    "Handler node type must be a non-array reference type: " + nodeType.getName());
        }

        HandlerVersion version = Objects.requireNonNull(handler.version(), "handler.version()");
        if (version.apiVersion() != HandlerVersion.CURRENT_API_VERSION) {
            throw new IllegalArgumentException("Handler " + version.id()
                    + " uses API version " + version.apiVersion()
                    + "; current API version is " + HandlerVersion.CURRENT_API_VERSION);
        }
        if (!version.supportsMinecraftVersion(minecraftVersion)) {
            throw new IllegalArgumentException("Handler " + version.id()
                    + " does not support Minecraft " + minecraftVersion
                    + " (supported range: " + version.minimumMinecraftVersion()
                    + " through " + version.maximumMinecraftVersion() + ")");
        }

        VerificationVectors vectors = Objects.requireNonNull(
                handler.verificationVectors(), "handler.verificationVectors()");

        if (handlersById.containsKey(version.id())) {
            throw new IllegalArgumentException("Duplicate handler ID: " + version.id());
        }
        RegisteredHandler<?> existingType = handlersByType.get(nodeType);
        if (existingType != null) {
            throw new IllegalArgumentException("Node type " + nodeType.getName()
                    + " is already handled by " + existingType.version().id());
        }

        RegisteredHandler<?> registered = new RegisteredHandler<>(
                handler, nodeType, version, vectors);
        handlersById.put(version.id(), registered);
        handlersByType.put(nodeType, registered);
    }

    /**
     * Prevents further registrations. Repeated calls are harmless.
     */
    public synchronized void freeze() {
        frozen = true;
    }

    /** Returns whether registration has been permanently closed. */
    public synchronized boolean isFrozen() {
        return frozen;
    }

    /** Returns the Minecraft release associated with this registry. */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    /** Returns the number of registered handlers. */
    public synchronized int size() {
        return handlersById.size();
    }

    /**
     * Returns an immutable snapshot ordered by stable handler ID.
     */
    public synchronized List<DensityNodeHandler<?>> handlers() {
        return handlersById.values().stream()
                .sorted(Comparator.comparing(entry -> entry.version().id()))
                .map(entry -> (DensityNodeHandler<?>) entry.handler())
                .toList();
    }

    /** Returns the registered handler with the supplied stable ID. */
    public synchronized Optional<DensityNodeHandler<?>> findById(String id) {
        Objects.requireNonNull(id, "id");
        RegisteredHandler<?> entry = handlersById.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.handler());
    }

    /**
     * Finds the most specific registered handler compatible with a node.
     *
     * <p>Only handlers whose declared node type is assignable from the actual
     * node class are considered. Their supports method may further narrow the
     * accepted instances. An empty result means that the node must remain on
     * the Java fallback path.
     *
     * @param node original node object
     * @return the unique most-specific handler, or empty when none matches
     * @throws AmbiguousHandlerException when equally specific handlers match
     */
    public synchronized Optional<DensityNodeHandler<?>> find(Object node) {
        Objects.requireNonNull(node, "node");
        Class<?> actualType = node.getClass();

        List<Match> matches = new ArrayList<>();
        for (RegisteredHandler<?> entry : handlersById.values()) {
            if (!entry.nodeType().isAssignableFrom(actualType)) {
                continue;
            }
            if (!entry.handler().supports(node)) {
                continue;
            }
            int distance = inheritanceDistance(actualType, entry.nodeType());
            if (distance != Integer.MAX_VALUE) {
                matches.add(new Match(entry, distance));
            }
        }

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        int minimumDistance = matches.stream()
                .mapToInt(Match::distance)
                .min()
                .orElseThrow();
        List<RegisteredHandler<?>> best = matches.stream()
                .filter(match -> match.distance() == minimumDistance)
                .map(Match::handler)
                .sorted(Comparator.comparing(entry -> entry.version().id()))
                .toList();

        if (best.size() != 1) {
            List<String> conflictingIds = best.stream()
                    .map(entry -> entry.version().id())
                    .toList();
            throw new AmbiguousHandlerException(actualType, conflictingIds);
        }

        return Optional.of(best.getFirst().handler());
    }

    private void requireMutable() {
        if (frozen) {
            throw new IllegalStateException("Handler registry is frozen");
        }
    }

    private static int inheritanceDistance(Class<?> actualType, Class<?> targetType) {
        if (actualType == targetType) {
            return 0;
        }

        ArrayDeque<TypeDistance> pending = new ArrayDeque<>();
        Map<Class<?>, Integer> visited = new HashMap<>();
        pending.addLast(new TypeDistance(actualType, 0));
        visited.put(actualType, 0);

        while (!pending.isEmpty()) {
            TypeDistance current = pending.removeFirst();
            if (current.type() == targetType) {
                return current.distance();
            }

            int nextDistance = current.distance() + 1;
            Class<?> superclass = current.type().getSuperclass();
            if (superclass != null && !visited.containsKey(superclass)) {
                visited.put(superclass, nextDistance);
                pending.addLast(new TypeDistance(superclass, nextDistance));
            }

            Class<?>[] interfaces = current.type().getInterfaces();
            java.util.Arrays.sort(interfaces, Comparator.comparing(Class::getName));
            for (Class<?> implemented : interfaces) {
                if (!visited.containsKey(implemented)) {
                    visited.put(implemented, nextDistance);
                    pending.addLast(new TypeDistance(implemented, nextDistance));
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    private record TypeDistance(Class<?> type, int distance) {
    }

    private record Match(RegisteredHandler<?> handler, int distance) {
    }

    private record RegisteredHandler<T>(
            DensityNodeHandler<T> handler,
            Class<T> nodeType,
            HandlerVersion version,
            VerificationVectors verificationVectors) {
    }

    /**
     * Indicates that multiple equally specific handlers accept the same node.
     */
    public static final class AmbiguousHandlerException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final String nodeType;
        private final List<String> handlerIds;

        private AmbiguousHandlerException(Class<?> nodeType, List<String> handlerIds) {
            super("Ambiguous handlers for " + nodeType.getName() + ": "
                    + String.join(", ", handlerIds));
            this.nodeType = nodeType.getName();
            this.handlerIds = List.copyOf(handlerIds);
        }

        public String nodeType() {
            return nodeType;
        }

        public List<String> handlerIds() {
            return handlerIds;
        }
    }
}
