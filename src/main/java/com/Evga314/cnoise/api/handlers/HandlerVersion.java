package com.Evga314.cnoise.api.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable compatibility and semantic version information for a density handler.
 *
 * @param id stable namespaced handler identifier
 * @param apiVersion public handler API version
 * @param semanticVersion handler semantic version
 * @param minimumMinecraftVersion inclusive minimum supported Minecraft release
 * @param maximumMinecraftVersion inclusive maximum supported Minecraft release
 */
public record HandlerVersion(
        String id,
        int apiVersion,
        String semanticVersion,
        String minimumMinecraftVersion,
        String maximumMinecraftVersion) {

    public static final int CURRENT_API_VERSION = 1;

    private static final Pattern ID_PATTERN =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern SEMANTIC_VERSION_PATTERN =
            Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
    private static final Pattern MINECRAFT_VERSION_PATTERN =
            Pattern.compile("\\d+(?:\\.\\d+){1,3}");

    public HandlerVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(semanticVersion, "semanticVersion");
        Objects.requireNonNull(minimumMinecraftVersion, "minimumMinecraftVersion");
        Objects.requireNonNull(maximumMinecraftVersion, "maximumMinecraftVersion");

        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Handler ID must use the namespace:path format: " + id);
        }
        if (apiVersion < 1) {
            throw new IllegalArgumentException("API version must be positive");
        }
        if (!SEMANTIC_VERSION_PATTERN.matcher(semanticVersion).matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + semanticVersion);
        }

        List<Integer> minimum = parseMinecraftVersion(minimumMinecraftVersion);
        List<Integer> maximum = parseMinecraftVersion(maximumMinecraftVersion);
        if (compareVersions(minimum, maximum) > 0) {
            throw new IllegalArgumentException(
                    "Minimum Minecraft version exceeds maximum: "
                            + minimumMinecraftVersion + " > " + maximumMinecraftVersion);
        }
    }

    /** Checks whether this handler supports the supplied Minecraft release. */
    public boolean supportsMinecraftVersion(String minecraftVersion) {
        List<Integer> candidate = parseMinecraftVersion(minecraftVersion);
        List<Integer> minimum = parseMinecraftVersion(minimumMinecraftVersion);
        List<Integer> maximum = parseMinecraftVersion(maximumMinecraftVersion);
        return compareVersions(candidate, minimum) >= 0
                && compareVersions(candidate, maximum) <= 0;
    }

    private static List<Integer> parseMinecraftVersion(String version) {
        Objects.requireNonNull(version, "minecraftVersion");
        if (!MINECRAFT_VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Expected a numeric dotted Minecraft release version: " + version);
        }

        String[] components = version.split("\\.");
        List<Integer> result = new ArrayList<>(components.length);
        for (String component : components) {
            try {
                result.add(Integer.parseInt(component));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "Minecraft version component is out of range: " + version, failure);
            }
        }
        return List.copyOf(result);
    }

    private static int compareVersions(List<Integer> left, List<Integer> right) {
        int count = Math.max(left.size(), right.size());
        for (int index = 0; index < count; index++) {
            int leftPart = index < left.size() ? left.get(index) : 0;
            int rightPart = index < right.size() ? right.get(index) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
