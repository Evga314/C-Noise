import net.minecraft.SharedConstants;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Random;

import static java.lang.foreign.ValueLayout.*;

/**
 * Bitwise differential tests against actual Minecraft scalar samplers.
 * All exports belong to the test bridge, not the production ABI.
 */
public final class BlendedMinecraftTest {
    private static final double DOMAIN = 0x1.0p40;
    private static long checks;
    private static final long[] branches = new long[3];

    private BlendedMinecraftTest() {
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Class<?> type = object.getClass();
        while (type != null) {
            for (Field candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(name)) {
                    candidate.setAccessible(true);
                    return candidate.get(object);
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static double number(Object object, String name) throws ReflectiveOperationException {
        return (Double) field(object, name);
    }

    private static void bits(float expected, float actual, String label,
                             double x, double y, double z) {
        checks++;
        int left = Float.floatToRawIntBits(expected);
        int right = Float.floatToRawIntBits(actual);
        if (left != right) {
            throw new AssertionError(label + " at (" + Double.toHexString(x)
                    + ", " + Double.toHexString(y) + ", " + Double.toHexString(z)
                    + "): Java=0x" + Integer.toHexString(left)
                    + ", C=0x" + Integer.toHexString(right));
        }
    }

    private static void coordinate(double value) {
        require(Double.isFinite(value) && Math.abs(value) <= DOMAIN,
                "Coordinate outside native contract: " + value);
    }

    private static MemorySegment perlin(Arena arena, SmearedPerlinNoise noise)
            throws ReflectiveOperationException {
        byte[] permutation = (byte[]) field(noise, "perms");
        require(permutation.length == 256, "Unexpected permutation length");
        boolean[] seen = new boolean[256];
        for (byte entry : permutation) {
            int value = Byte.toUnsignedInt(entry);
            require(!seen[value], "Duplicate permutation entry");
            seen[value] = true;
        }
        MemorySegment state = arena.allocate(280, 8);
        MemorySegment.copy(MemorySegment.ofArray(permutation), 0, state, 0, 256);
        String[] names = {"offsetX", "offsetY", "offsetZ"};
        for (int axis = 0; axis < names.length; axis++) {
            double offset = number(noise, names[axis]);
            require(offset >= 0.0 && offset < 256.0, "Invalid Perlin offset");
            state.set(JAVA_DOUBLE, 256L + axis * 8L, offset);
        }
        return state;
    }

    private record StackSnapshot(NoiseStack reference, MemorySegment state,
                                 double[] frequencies) {
        void validate(double x, double y, double z) {
            coordinate(x);
            coordinate(y);
            coordinate(z);
            for (double frequency : frequencies) {
                coordinate(x * frequency);
                coordinate(y * frequency);
                coordinate(z * frequency);
            }
        }
    }

    private static StackSnapshot stack(Arena arena, NoiseStack reference)
            throws ReflectiveOperationException {
        Object[] layers = (Object[]) field(reference, "layers");
        MemorySegment entries = layers.length == 0
                ? MemorySegment.NULL : arena.allocate(32L * layers.length, 8);
        double[] frequencies = new double[layers.length];
        for (int index = 0; index < layers.length; index++) {
            Object layer = layers[index];
            Object noise = field(layer, "noise");
            require(noise instanceof SmearedPerlinNoise, "Unexpected stack layer type");
            SmearedPerlinNoise smeared = (SmearedPerlinNoise) noise;
            double frequency = number(layer, "frequency");
            double fudge = number(smeared, "fudgeYScale");
            float amplitude = (Float) field(layer, "amplitude");
            require(Double.isFinite(frequency) && Float.isFinite(amplitude),
                    "Nonfinite stack parameter");
            require(fudge >= 0x1.0p-30 && fudge <= 0x1.0p24,
                    "Smear scale outside native contract: " + fudge);
            frequencies[index] = frequency;
            long base = 32L * index;
            entries.set(ADDRESS, base, perlin(arena, smeared));
            entries.set(JAVA_DOUBLE, base + 8, frequency);
            entries.set(JAVA_DOUBLE, base + 16, fudge);
            entries.set(JAVA_FLOAT, base + 24, amplitude);
        }
        MemorySegment state = arena.allocate(16, 8);
        state.set(ADDRESS, 0, entries);
        state.set(JAVA_INT, 8, layers.length);
        return new StackSnapshot(reference, state, frequencies);
    }

    private static final class NativeKernel {
        private final MethodHandle smeared;
        private final MethodHandle stack;
        private final MethodHandle blended;

        NativeKernel(Path library, Arena arena) throws Throwable {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, arena);
            layout(linker, symbols, "cnoise_test_perlin_layout",
                    new long[]{280, 8, 0, 256, 264, 272, 24, 8, 0, 8, 16});
            layout(linker, symbols, "cnoise_test_blended_layout",
                    new long[]{32, 8, 0, 8, 16, 24, 16, 8, 0, 8,
                            80, 8, 0, 16, 32, 48, 56, 64, 72});
            smeared = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_smeared_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_DOUBLE,
                            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            stack = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_smeared_stack_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS,
                            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            blended = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_blended_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        }

        private static void layout(Linker linker, SymbolLookup symbols,
                                   String name, long[] expected) throws Throwable {
            MethodHandle handle = linker.downcallHandle(symbols.findOrThrow(name),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
            for (int index = 0; index < expected.length; index++) {
                long actual = (long) handle.invokeExact(index);
                require(actual == expected[index], name + "[" + index + "]=" + actual);
            }
            long invalid = (long) handle.invokeExact(expected.length);
            require(invalid == -1L, name + " must reject unknown field");
        }

        float smeared(MemorySegment state, double scale, double x, double y, double z)
                throws Throwable {
            return (float) smeared.invokeExact(state, scale, x, y, z);
        }

        float stack(MemorySegment state, double x, double y, double z) throws Throwable {
            return (float) stack.invokeExact(state, x, y, z);
        }

        float blended(MemorySegment state, int x, int y, int z) throws Throwable {
            return (float) blended.invokeExact(state, x, y, z);
        }
    }

    private static void testSmeared(NativeKernel kernel, Arena arena) throws Throwable {
        double[] scales = {0x1.0p-30, 0.001, 0.5, 1.0, 8.0, 684.412, 0x1.0p24};
        double[] edges = {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                -256.0, -1.0, Math.nextDown(-1.0), Math.nextUp(-1.0),
                Math.nextDown(1.0), 1.0, Math.nextUp(1.0), 256.0,
                Math.nextDown(16777216.0), 16777216.0, Math.nextUp(16777216.0),
                -33554432.0, 33554432.0, -DOMAIN, DOMAIN};
        for (long seed : new long[]{0, -1, Long.MAX_VALUE}) {
            for (double scale : scales) {
                SmearedPerlinNoise reference =
                        new SmearedPerlinNoise(RandomSource.create(seed), scale);
                MemorySegment state = perlin(arena, reference);
                double actualScale = number(reference, "fudgeYScale");
                String label = "Smeared seed=" + seed + " scale=" + scale;
                for (int pass = 0; pass < 2; pass++) {
                    for (double x : edges) {
                        for (double y : edges) {
                            bits(reference.get(x, y, -0.25),
                                    kernel.smeared(state, actualScale, x, y, -0.25),
                                    label, x, y, -0.25);
                        }
                    }
                    double offsetY = number(reference, "offsetY");
                    for (int cell = -8; cell <= 8; cell++) {
                        double boundary = cell - offsetY;
                        for (double y : new double[]{
                                Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
                            bits(reference.get(0.125, y, -0.75),
                                    kernel.smeared(state, actualScale, 0.125, y, -0.75),
                                    label + " cell boundary", 0.125, y, -0.75);
                        }
                    }
                    Random random = new Random(seed ^ 0x35719L);
                    for (int index = 0; index < 3000; index++) {
                        double extent = index % 2 == 0 ? DOMAIN : 256.0;
                        double x = (random.nextDouble() * 2.0 - 1.0) * extent;
                        double y = (random.nextDouble() * 2.0 - 1.0) * extent;
                        double z = (random.nextDouble() * 2.0 - 1.0) * extent;
                        bits(reference.get(x, y, z),
                                kernel.smeared(state, actualScale, x, y, z),
                                label, x, y, z);
                    }
                }
            }
        }
    }

    private static void testBlended(NativeKernel kernel, Arena arena,
                                    double[] settings, long seed) throws Throwable {
        BlendedNoise definition = new BlendedNoise(
                settings[0], settings[1], settings[2], settings[3], settings[4]);
        DensitySampler reference = definition.compileSampler(RandomSource.create(seed));
        Object first = field(reference, "first");
        Object second = field(reference, "second");
        Object clamp = field(reference, "alpha");
        Object add = field(clamp, "input");
        Object main = field(add, "left");
        require((Float) field(clamp, "min") == 0.0f
                        && (Float) field(clamp, "max") == 1.0f
                        && (Float) field(add, "right") == 0.5f,
                "Unexpected compiled blend expression");
        Object[] samplers = {first, second, main};
        StackSnapshot[] stacks = new StackSnapshot[3];
        double[] xz = new double[3];
        double[] ys = new double[3];
        MemorySegment state = arena.allocate(80, 8);
        for (int index = 0; index < samplers.length; index++) {
            stacks[index] = stack(arena, (NoiseStack) field(samplers[index], "noise"));
            xz[index] = number(samplers[index], "xzScale");
            ys[index] = number(samplers[index], "yScale");
            require(Double.isFinite(xz[index]) && Double.isFinite(ys[index]),
                    "Invalid compiled multiplier");
            MemorySegment.copy(stacks[index].state(), 0, state, index * 16L, 16);
        }
        require(Double.doubleToRawLongBits(xz[0]) == Double.doubleToRawLongBits(xz[1])
                        && Double.doubleToRawLongBits(ys[0]) == Double.doubleToRawLongBits(ys[1]),
                "Limit samplers have different scales");
        state.set(JAVA_DOUBLE, 48, xz[0]);
        state.set(JAVA_DOUBLE, 56, ys[0]);
        state.set(JAVA_DOUBLE, 64, xz[2]);
        state.set(JAVA_DOUBLE, 72, ys[2]);
        int[] edges = {0, -1, 1, -16, 16, -64, 320, -30000000, 30000000};
        String label = "Blended seed=" + seed + " xzScale=" + settings[0];
        for (int pass = 0; pass < 2; pass++) {
            Random random = new Random(seed ^ 0x67132L);
            int edgeCount = edges.length * edges.length * edges.length;
            for (int index = 0; index < edgeCount + 12000; index++) {
                int x;
                int y;
                int z;
                if (index < edgeCount) {
                    x = edges[index % edges.length];
                    y = edges[(index / edges.length) % edges.length];
                    z = edges[index / (edges.length * edges.length)];
                } else {
                    int extent = index % 2 == 0 ? 30000000 : 512;
                    x = random.nextInt(2 * extent + 1) - extent;
                    y = random.nextInt(2 * extent + 1) - extent;
                    z = random.nextInt(2 * extent + 1) - extent;
                }
                for (int layer = 0; layer < stacks.length; layer++) {
                    double sx = x * xz[layer];
                    double sy = y * ys[layer];
                    double sz = z * xz[layer];
                    stacks[layer].validate(sx, sy, sz);
                    if (index < edgeCount || index % 97 == 0) {
                        bits(stacks[layer].reference().get(sx, sy, sz),
                                kernel.stack(stacks[layer].state(), sx, sy, sz),
                                label + " stack=" + layer, sx, sy, sz);
                    }
                }
                float alpha = ((DensitySampler) clamp).sampleValue(null, x, y, z);
                require(Float.isFinite(alpha) && alpha >= 0.0f && alpha <= 1.0f,
                        "Invalid blend alpha");
                branches[alpha == 0.0f ? 0 : alpha == 1.0f ? 2 : 1]++;
                bits(reference.sampleValue(null, x, y, z),
                        kernel.blended(state, x, y, z), label, x, y, z);
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: BlendedMinecraftTest <test-library>");
        }
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        require(!SharedConstants.DEBUG_ENABLE_FARLANDS,
                "Farlands mode is outside the native contract");
        try (Arena arena = Arena.ofConfined()) {
            NativeKernel kernel = new NativeKernel(Path.of(args[0]).toAbsolutePath(), arena);
            testSmeared(kernel, arena);
            MemorySegment empty = arena.allocate(16, 8);
            empty.set(ADDRESS, 0, MemorySegment.NULL);
            empty.set(JAVA_INT, 8, 0);
            bits(0.0f, kernel.stack(empty, 0.0, 0.0, 0.0),
                    "Empty smeared stack", 0.0, 0.0, 0.0);
            double[][] settings = {
                    {0.25, 0.125, 80.0, 160.0, 8.0},
                    {1.0, 1.0, 80.0, 160.0, 8.0},
                    {0.01, 0.02, 1.0, 2.0, 1.0}
            };
            for (double[] configuration : settings) {
                for (long seed : new long[]{0, -1, Long.MAX_VALUE}) {
                    testBlended(kernel, arena, configuration, seed);
                }
            }
        }
        for (int branch = 0; branch < branches.length; branch++) {
            require(branches[branch] > 0, "Uncovered interpolation branch " + branch);
        }
        System.out.println("Minecraft Blended differential: " + checks + " checks passed");
        System.out.println("Branches: min=" + branches[0] + ", interpolation="
                + branches[1] + ", max=" + branches[2]);
        System.out.println("Reference: "
                + BlendedNoise.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
