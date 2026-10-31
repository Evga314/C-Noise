import net.minecraft.SharedConstants;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

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
 * Differential tests against the Minecraft classes on the runtime classpath.
 * Uses a separate test DLL, never exports internal kernels from production.
 */
public final class PerlinMinecraftTest {
    private static final long STATE_SIZE = 280;
    private static final long LAYER_SIZE = 24;
    private static final double DOMAIN = 0x1.0p40;
    private static long checks;

    private PerlinMinecraftTest() {
    }

    private static final class ExportablePerlin extends PerlinNoise {
        ExportablePerlin(long seed) {
            super(RandomSource.create(seed));
        }

        MemorySegment snapshot(Arena arena) {
            if (perms.length != 256) {
                throw new AssertionError("Unexpected Minecraft permutation size");
            }
            boolean[] seen = new boolean[256];
            for (byte entry : perms) {
                int value = Byte.toUnsignedInt(entry);
                if (seen[value]) {
                    throw new AssertionError("Duplicate permutation entry");
                }
                seen[value] = true;
            }
            for (double offset : new double[]{offsetX, offsetY, offsetZ}) {
                if (!(offset >= 0.0 && offset < 256.0)) {
                    throw new AssertionError("Offset outside kernel contract: " + offset);
                }
            }
            MemorySegment state = arena.allocate(STATE_SIZE, 8);
            MemorySegment.copy(MemorySegment.ofArray(perms), 0, state, 0, 256);
            state.set(JAVA_DOUBLE, 256, offsetX);
            state.set(JAVA_DOUBLE, 264, offsetY);
            state.set(JAVA_DOUBLE, 272, offsetZ);
            return state;
        }

        double[] offsets() {
            return new double[]{offsetX, offsetY, offsetZ};
        }
    }

    private static final class NativeKernel {
        private final MethodHandle sample;
        private final MethodHandle stack;

        NativeKernel(Path library, Arena arena) throws Throwable {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, arena);
            MethodHandle layout = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_perlin_layout"),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
            long[] expected = {280, 8, 0, 256, 264, 272, 24, 8, 0, 8, 16};
            for (int index = 0; index < expected.length; index++) {
                long actual = (long) layout.invokeExact(index);
                require(actual == expected[index],
                        "Native layout field " + index + ": " + actual);
            }
            long invalid = (long) layout.invokeExact(expected.length);
            require(invalid == -1L, "Unknown layout field must return UINT64_MAX");

            sample = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_perlin_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS,
                            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            stack = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_perlin_stack_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_INT,
                            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        }

        float sample(MemorySegment state, double x, double y, double z) throws Throwable {
            return (float) sample.invokeExact(state, x, y, z);
        }

        float stack(MemorySegment layers, int count, double x, double y, double z)
                throws Throwable {
            return (float) stack.invokeExact(layers, count, x, y, z);
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PerlinMinecraftTest <test-library>");
        }
        require(!SharedConstants.DEBUG_ENABLE_FARLANDS,
                "Farlands debug mode is outside the native kernel contract");
        Path library = Path.of(args[0]).toAbsolutePath();
        try (Arena arena = Arena.ofConfined()) {
            NativeKernel nativeKernel = new NativeKernel(library, arena);
            long[] seeds = {0, 1, -1, 42, 314159265, Long.MIN_VALUE, Long.MAX_VALUE};
            for (long seed : seeds) {
                testSampler(nativeKernel, arena, seed);
            }
            testStacks(nativeKernel, arena);
        }
        System.out.println("Minecraft Perlin differential: " + checks + " checks passed");
        System.out.println("Reference: "
                + PerlinNoise.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void bits(float expected, float actual, String label,
                             double x, double y, double z) {
        checks++;
        int expectedBits = Float.floatToRawIntBits(expected);
        int actualBits = Float.floatToRawIntBits(actual);
        if (expectedBits != actualBits) {
            throw new AssertionError(label
                    + " at (" + Double.toHexString(x) + ", "
                    + Double.toHexString(y) + ", " + Double.toHexString(z)
                    + "): Java=0x" + Integer.toHexString(expectedBits)
                    + ", C=0x" + Integer.toHexString(actualBits));
        }
    }

    private static void compare(NativeKernel kernel, ExportablePerlin reference,
                                MemorySegment state, String label,
                                double x, double y, double z) throws Throwable {
        float expected = reference.get(x, y, z);
        float actual = kernel.sample(state, x, y, z);
        bits(expected, actual, label, x, y, z);
    }

    private static void testSampler(NativeKernel kernel, Arena arena, long seed)
            throws Throwable {
        ExportablePerlin reference = new ExportablePerlin(seed);
        MemorySegment state = reference.snapshot(arena);
        String label = "Perlin seed=" + seed;
        double half = Math.nextDown(16777216.0);
        double[] edges = {
                0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL,
                -256.0, -1.0, -0.5, 0.5, 1.0, 255.0, 256.0,
                Math.nextDown(1.0), Math.nextUp(1.0),
                Math.nextDown(-1.0), Math.nextUp(-1.0),
                Math.nextDown(half), half, Math.nextUp(half),
                Math.nextDown(-half), -half, Math.nextUp(-half),
                Math.nextDown(33554432.0), 33554432.0, Math.nextUp(33554432.0),
                -33554432.0, -30000000.0, 30000000.0,
                -DOMAIN, Math.nextUp(-DOMAIN), Math.nextDown(DOMAIN), DOMAIN
        };
        // Repeat the exact edge corpus before and after substantial JVM warm-up.
        for (int pass = 0; pass < 2; pass++) {
            for (double x : edges) {
                for (double y : edges) {
                    compare(kernel, reference, state, label, x, y, -0.25);
                    compare(kernel, reference, state, label, y, 0.125, x);
                    compare(kernel, reference, state, label, -0.75, x, y);
                }
            }
            double[] offsets = reference.offsets();
            for (int cell = -16; cell <= 16; cell++) {
                for (int axis = 0; axis < 3; axis++) {
                    double boundary = cell - offsets[axis];
                    for (double value : new double[]{
                            Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
                        double[] point = {0.125, -0.75, 4.5};
                        point[axis] = value;
                        compare(kernel, reference, state, label + " cell boundary",
                                point[0], point[1], point[2]);
                    }
                }
            }
            Random random = new Random(seed ^ 0x53e9b47d12L);
            for (int index = 0; index < 20000; index++) {
                double scale = switch (index % 3) {
                    case 0 -> 512.0;
                    case 1 -> 30000000.0;
                    default -> DOMAIN;
                };
                double x = (random.nextDouble() * 2.0 - 1.0) * scale;
                double y = (random.nextDouble() * 2.0 - 1.0) * scale;
                double z = (random.nextDouble() * 2.0 - 1.0) * scale;
                compare(kernel, reference, state, label, x, y, z);
            }
        }
    }

    private static void testStacks(NativeKernel kernel, Arena arena) throws Throwable {
        bits(0.0f, kernel.stack(MemorySegment.NULL, 0, 0.0, 0.0, 0.0),
                "Empty native stack", 0.0, 0.0, 0.0);

        Field layersField = NoiseStack.class.getDeclaredField("layers");
        layersField.setAccessible(true);
        Class<?> layerType = layersField.getType().getComponentType();
        Field noiseField = layerType.getDeclaredField("noise");
        Field frequencyField = layerType.getDeclaredField("frequency");
        Field amplitudeField = layerType.getDeclaredField("amplitude");
        noiseField.setAccessible(true);
        frequencyField.setAccessible(true);
        amplitudeField.setAccessible(true);
        for (int scenario = 0; scenario < 4; scenario++) {
            NoiseStack.Builder builder = NoiseStack.builder();
            int requestedCount = scenario == 0 ? 1 : 8;
            for (int index = 0; index < requestedCount; index++) {
                double frequency = Math.scalb(1.0, -index);
                if (scenario == 2) {
                    frequency = -frequency;
                }
                float amplitude = switch (scenario) {
                    case 0 -> 1.0f;
                    case 1 -> Math.scalb(1.0f, -index);
                    case 2 -> (index % 2 == 0 ? -0.75f : 0.5f);
                    default -> index == 0 ? Float.MIN_NORMAL : 0.0f;
                };
                builder.add(new ExportablePerlin(1000L + index), frequency, amplitude);
            }
            NoiseStack reference = builder.build();
            // Builder may normalize its input; copy actual retained layer order.
            Object[] layers = (Object[]) layersField.get(reference);
            MemorySegment nativeLayers = layers.length == 0
                    ? MemorySegment.NULL : arena.allocate(LAYER_SIZE * layers.length, 8);
            for (int index = 0; index < layers.length; index++) {
                Object layer = layers[index];
                Object noise = noiseField.get(layer);
                double frequency = frequencyField.getDouble(layer);
                float amplitude = amplitudeField.getFloat(layer);
                require(noise instanceof ExportablePerlin,
                        "Unexpected non-Perlin layer after NoiseStack.Builder");
                require(Double.isFinite(frequency)
                                && Math.abs(frequency) <= 1.0
                                && Float.isFinite(amplitude),
                        "Stack parameters outside test domain");
                MemorySegment state = ((ExportablePerlin) noise).snapshot(arena);
                long base = index * LAYER_SIZE;
                nativeLayers.set(ADDRESS, base, state);
                nativeLayers.set(JAVA_DOUBLE, base + 8, frequency);
                nativeLayers.set(JAVA_FLOAT, base + 16, amplitude);
            }
            Random random = new Random(87123L);
            for (int index = 0; index < 20000; index++) {
                double scale = index % 2 == 0 ? DOMAIN : 256.0;
                double x = (random.nextDouble() * 2.0 - 1.0) * scale;
                double y = (random.nextDouble() * 2.0 - 1.0) * scale;
                double z = (random.nextDouble() * 2.0 - 1.0) * scale;
                float expected = reference.get(x, y, z);
                float actual = kernel.stack(nativeLayers, layers.length, x, y, z);
                bits(expected, actual, "NoiseStack scenario=" + scenario, x, y, z);
            }
        }
    }
}
