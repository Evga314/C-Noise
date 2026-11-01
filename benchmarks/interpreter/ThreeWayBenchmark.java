<escape>import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Instruction;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode;

import net.minecraft.SharedConstants;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode.*;

 /**
  * Standalone three-way benchmark for Perlin and arithmetic batch evaluation.
  *
  * W1 compares Minecraft Java Perlin with scalar native FFM calls.
  * W2 compares a recursive object tree, explicit Java batch arithmetic,
  * and the existing native ArithmeticProgram batch bridge.
  *
  * All correctness gates run before any performance measurements.
  * Все проверки корректности выполняются до начала измерений.
  */
public final class ThreeWayBenchmark {
    private static final int[] BATCH_SIZES = {256, 4096, 32768, 65536};
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURED_ITERATIONS = 15;

    private static final long PERLIN_SEED = 0x434e4f495345L;
    private static final long PERLIN_INPUT_SEED = 0x5045524c494eL;
    private static final long ARITHMETIC_INPUT_SEED = 0x41524954484dL;

    private static final double PERLIN_COORDINATE_DOMAIN = 30_000_000.0;
    private static final double PERLIN_STATE_SIZE = 280.0;

    private static final long INITIAL_CHECKSUM = 0xcbf29ce484222325L;

    private ThreeWayBenchmark() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ThreeWayBenchmark <perlin-test-library> <arithmetic-library>");
        }

        Path perlinLibrary = Path.of(args[0]).toAbsolutePath();
        Path arithmeticLibrary = Path.of(args[1]).toAbsolutePath();

        Path reportPath = Path.of("benchmarks", "results",
                "three-way-" + LocalDate.now() + ".md");

        if (Files.exists(reportPath)) {
            throw new IOException("Refusing to overwrite existing benchmark report: "
                    + reportPath.toAbsolutePath());
        }

        StringBuilder report = new StringBuilder(16_384);
        appendMetadata(report, perlinLibrary, arithmeticLibrary);

        try {
            execute(perlinLibrary, arithmeticLibrary, report);
            report.append("\n## Final status / Итог\n\n");
            report.append("**PASS** — all correctness gates passed and all measurements completed.\n");
        } catch (Throwable failure) {
            report.append("\n## Final status / Итог\n\n");
            report.append("**FAILED** — benchmark stopped without publishing incomplete timings.\n\n");
            report.append("```text\n");
            report.append(failure.getClass().getName()).append(": ")
                    .append(String.valueOf(failure.getMessage())).append('\n');
            report.append("```\n");

            writeReport(reportPath, report);
            System.err.println("Benchmark stopped: " + failure);
            System.err.println("Diagnostic report: " + reportPath.toAbsolutePath());
            throw failure;
        }

        writeReport(reportPath, report);
        System.out.println("Benchmark report: " + reportPath.toAbsolutePath());
    }

    private static void execute(
            Path perlinLibrary,
            Path arithmeticLibrary,
            StringBuilder report
    ) throws Throwable {
        if (!Files.isRegularFile(perlinLibrary)) {
            throw new IOException("Perlin test library does not exist: " + perlinLibrary);
        }
        if (!Files.isRegularFile(arithmeticLibrary)) {
            throw new IOException("Arithmetic library does not exist: " + arithmeticLibrary);
        }
        if (SharedConstants.DEBUG_ENABLE_FARLANDS) {
            throw new IllegalStateException(
                    "Farlands debug mode is outside the native Perlin kernel contract.");
        }

        Inputs[] perlinInputs = new Inputs[BATCH_SIZES.length];
        Inputs[] arithmeticInputs = new Inputs[BATCH_SIZES.length];

        for (int index = 0; index < BATCH_SIZES.length; index++) {
            int size = BATCH_SIZES[index];
            perlinInputs[index] = createPerlinInputs(size);
            arithmeticInputs[index] = createArithmeticInputs(size);
        }

        Node arithmeticTree = buildArithmeticTree();
        int operationCount = arithmeticTree.operationCount();

        if (operationCount < 24 || operationCount > 40) {
            throw new IllegalStateException(
                    "Arithmetic workload must contain 24-40 operations; got "
                            + operationCount);
        }

        ProgramBundle arithmeticProgram = compileArithmeticProgram(arithmeticTree);

        report.append("## Workloads / Нагрузки\n\n");
        report.append("- **W1 Perlin:** Minecraft `PerlinNoise.get` versus scalar native "
                + "Perlin FFM sampling. The native contender performs one scalar downcall "
                + "per point; it is not a native batch API.\n");
        report.append("- **W2 Arithmetic:** recursive object-tree interpreter, "
                + "explicit Java batch loop, and native `ArithmeticProgram` batch.\n");
        report.append("- **W2 operation count:** ").append(operationCount).append('\n');
        report.append("- **W2 native instruction count:** ")
                .append(arithmeticProgram.program.instructionCount()).append('\n');
        report.append("- **Batch sizes:** ").append(Arrays.toString(BATCH_SIZES)).append('\n');
        report.append("- **Warmup:** ").append(WARMUP_ITERATIONS)
                .append(" iterations per contender and size.\n");
        report.append("- **Measurements:** ").append(MEASURED_ITERATIONS)
                .append(" iterations per contender and size.\n\n");

        try (Arena arena = Arena.ofConfined();
             NativeArithmeticBridge arithmeticBridge =
                     new NativeArithmeticBridge(arithmeticLibrary)) {

            ExportablePerlin perlinReference = new ExportablePerlin(PERLIN_SEED);
            MemorySegment perlinState = perlinReference.snapshot(arena);
            NativePerlinKernel perlinKernel = new NativePerlinKernel(perlinLibrary, arena);

            report.append("## Correctness gates / Проверки корректности\n\n");
            report.append("All comparisons use raw IEEE-754 bits. "
                    + "No tolerance-based admission is permitted.\n\n");
            report.append("| Workload | Batch size | Result |\n");
            report.append("|---|---:|---|\n");

            // Gate every W1 size before starting any measurements.
            for (int index = 0; index < BATCH_SIZES.length; index++) {
                int size = BATCH_SIZES[index];
                gatePerlin(perlinReference, perlinKernel, perlinState,
                        perlinInputs[index]);
                report.append("| W1 Perlin | ").append(size)
                        .append(" | PASS |\n");
                System.out.println("W1 correctness passed: " + size);
            }

            // Gate every W2 size before starting any measurements.
            for (int index = 0; index < BATCH_SIZES.length; index++) {
                int size = BATCH_SIZES[index];
                gateArithmetic(arithmeticTree, arithmeticProgram.program,
                        arithmeticBridge, arithmeticInputs[index]);
                report.append("| W2 Arithmetic | ").append(size)
                        .append(" | PASS |\n");
                System.out.println("W2 correctness passed: " + size);
            }

            report.append("\n**All correctness gates passed. Measurements may begin.**\n\n");

            report.append("## W1 — Perlin measurements\n\n");
            report.append("| Batch | Contender | Median ns/point | Minimum ns/point | Checksum |\n");
            report.append("|---:|---|---:|---:|---:|\n");

            for (int index = 0; index < BATCH_SIZES.length; index++) {
                Inputs inputs = perlinInputs[index];
                int size = inputs.size();

                float[] javaOutput = new float[size];
                float[] nativeOutput = new float[size];

                TimedAction[] actions = {
                        () -> perlinJavaBatch(perlinReference, inputs, javaOutput),
                        () -> perlinNativeBatch(perlinKernel, perlinState,
                                inputs, nativeOutput)
                };

                ChecksumAction[] checksums = {
                        () -> checksum(javaOutput),
                        () -> checksum(nativeOutput)
                };

                List<Measurement> measurements = measure(size, actions, checksums);
                appendMeasurements(report, size, measurements);
            }

            report.append("\n## W2 — Arithmetic measurements\n\n");
            report.append("| Batch | Contender | Median ns/point | Minimum ns/point | Checksum |\n");
            report.append("|---:|---|---:|---:|---:|\n");

            for (int index = 0; index < BATCH_SIZES.length; index++) {
                Inputs inputs = arithmeticInputs[index];
                int size = inputs.size();

                double[] treeOutput = new double[size];
                double[] javaOutput = new double[size];
                double[] nativeOutput = new double[size];

                try (var context = arithmeticBridge.createContext(
                        arithmeticProgram.program, size)) {

                    TimedAction[] actions = {
                            () -> objectTreeBatch(arithmeticTree, inputs, treeOutput),
                            () -> optimizedJavaBatch(inputs, javaOutput),
                            () -> context.evaluate(
                                    inputs.x, inputs.y, inputs.z, nativeOutput, size)
                    };

                    ChecksumAction[] checksums = {
                            () -> checksum(treeOutput),
                            () -> checksum(javaOutput),
                            () -> checksum(nativeOutput)
                    };

                    List<Measurement> measurements = measure(size, actions, checksums);
                    appendMeasurements(report, size, measurements);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // W1: Minecraft Perlin reference and existing scalar test FFM bridge.
    // ---------------------------------------------------------------------

    private static final class ExportablePerlin extends PerlinNoise {
        ExportablePerlin(long seed) {
            super(RandomSource.create(seed));
        }

        MemorySegment snapshot(Arena arena) {
            if (perms.length != 256) {
                throw new IllegalStateException(
                        "Unexpected Minecraft permutation size: " + perms.length);
            }

            boolean[] seen = new boolean[256];
            for (byte entry : perms) {
                int value = Byte.toUnsignedInt(entry);
                if (seen[value]) {
                    throw new IllegalStateException("Duplicate permutation entry: " + value);
                }
                seen[value] = true;
            }

            for (double offset : new double[]{offsetX, offsetY, offsetZ}) {
                if (!(offset >= 0.0 && offset < 256.0)) {
                    throw new IllegalStateException(
                            "Perlin offset outside kernel contract: " + offset);
                }
            }

            MemorySegment state = arena.allocate((long) PERLIN_STATE_SIZE, 8);
            MemorySegment.copy(MemorySegment.ofArray(perms), 0, state, 0, 256);
            state.set(JAVA_DOUBLE, 256, offsetX);
            state.set(JAVA_DOUBLE, 264, offsetY);
            state.set(JAVA_DOUBLE, 272, offsetZ);
            return state;
        }
    }

    private static final class NativePerlinKernel {
        private final MethodHandle sample;

        NativePerlinKernel(Path library, Arena arena) throws Throwable {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, arena);

            MethodHandle layout = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_perlin_layout"),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_INT));

            long[] expected = {280, 8, 0, 256, 264, 272, 24, 8, 0, 8, 16};

            for (int index = 0; index < expected.length; index++) {
                long actual = (long) layout.invokeExact(index);
                if (actual != expected[index]) {
                    throw new IllegalStateException(
                            "Perlin ABI layout mismatch at field " + index
                                    + ": expected " + expected[index]
                                    + ", got " + actual);
                }
            }

            long invalid = (long) layout.invokeExact(expected.length);
            if (invalid != -1L) {
                throw new IllegalStateException(
                        "Perlin ABI invalid-field sentinel mismatch: " + invalid);
            }

            sample = linker.downcallHandle(
                    symbols.findOrThrow("cnoise_test_perlin_sample"),
                    FunctionDescriptor.of(JAVA_FLOAT, ADDRESS,
                            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        }

        float sample(MemorySegment state, double x, double y, double z)
                throws Throwable {
            return (float) sample.invokeExact(state, x, y, z);
        }
    }

    private static Inputs createPerlinInputs(int size) {
        Random random = new Random(PERLIN_INPUT_SEED ^ size);

        double[] x = new double[size];
        double[] y = new double[size];
        double[] z = new double[size];

        for (int index = 0; index < size; index++) {
            x[index] = coordinate(random, PERLIN_COORDINATE_DOMAIN);
            y[index] = coordinate(random, PERLIN_COORDINATE_DOMAIN);
            z[index] = coordinate(random, PERLIN_COORDINATE_DOMAIN);
        }

        double[] edges = {
                0.0, -0.0,
                Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL,
                -256.0, -1.0, 1.0, 256.0,
                -30_000_000.0, 30_000_000.0
        };

        for (int index = 0; index < Math.min(size, edges.length); index++) {
            x[index] = edges[index];
            y[index] = edges[(index + 3) % edges.length];
            z[index] = edges[(index + 7) % edges.length];
        }

        return new Inputs(x, y, z);
    }

    private static Inputs createArithmeticInputs(int size) {
        Random random = new Random(ARITHMETIC_INPUT_SEED ^ size);

        double[] x = new double[size];
        double[] y = new double[size];
        double[] z = new double[size];

        for (int index = 0; index < size; index++) {
            x[index] = coordinate(random, 1.0);
            y[index] = coordinate(random, 1.0);
            z[index] = coordinate(random, 1.0);
        }

        double[] edges = {
                0.0, -0.0,
                Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL,
                -1.0, 1.0, -0.5, 0.5
        };

        for (int index = 0; index < Math.min(size, edges.length); index++) {
            x[index] = edges[index];
            y[index] = edges[(index + 3) % edges.length];
            z[index] = edges[(index + 6) % edges.length];
        }

        return new Inputs(x, y, z);
    }

    private static double coordinate(Random random, double scale) {
        return (random.nextDouble() * 2.0 - 1.0) * scale;
    }

    private static void gatePerlin(
            ExportablePerlin reference,
            NativePerlinKernel nativeKernel,
            MemorySegment state,
            Inputs inputs
    ) throws Throwable {
        int size = inputs.size();
        float[] javaOutput = new float[size];
        float[] nativeOutput = new float[size];

        perlinJavaBatch(reference, inputs, javaOutput);
        perlinNativeBatch(nativeKernel, state, inputs, nativeOutput);

        for (int index = 0; index < size; index++) {
            int expected = Float.floatToRawIntBits(javaOutput[index]);
            int actual = Float.floatToRawIntBits(nativeOutput[index]);

            if (expected != actual) {
                throw new IllegalStateException(
                        "W1 Perlin raw-bit mismatch"
                                + "; size=" + size
                                + "; index=" + index
                                + "; Java=0x" + Integer.toHexString(expected)
                                + "; native=0x" + Integer.toHexString(actual));
            }
        }
    }

    private static void perlinJavaBatch(
            ExportablePerlin reference,
            Inputs inputs,
            float[] output
    ) {
        for (int index = 0; index < inputs.size(); index++) {
            output[index] = reference.get(
                    inputs.x[index], inputs.y[index], inputs.z[index]);
        }
    }

    private static void perlinNativeBatch(
            NativePerlinKernel kernel,
            MemorySegment state,
            Inputs inputs,
            float[] output
    ) throws Throwable {
        for (int index = 0; index < inputs.size(); index++) {
            output[index] = kernel.sample(
                    state, inputs.x[index], inputs.y[index], inputs.z[index]);
        }
    }

    // ---------------------------------------------------------------------
    // W2: one arithmetic expression tree and three independent evaluators.
    // ---------------------------------------------------------------------

    private enum NodeKind {
        COORDINATE,
        CONSTANT,
        OPERATION
    }

    private enum Coordinate {
        X,
        Y,
        Z
    }

    private static final class Node {
        private final NodeKind kind;
        private final Coordinate coordinate;
        private final double constant;
        private final Opcode opcode;
        private final Node first;
        private final Node second;
        private final Node third;

        private Node(
                NodeKind kind,
                Coordinate coordinate,
                double constant,
                Opcode opcode,
                Node first,
                Node second,
                Node third
        ) {
            this.kind = kind;
            this.coordinate = coordinate;
            this.constant = constant;
            this.opcode = opcode;
            this.first = first;
            this.second = second;
            this.third = third;
        }

        static Node coordinate(Coordinate coordinate) {
            return new Node(NodeKind.COORDINATE, coordinate,
                    0.0, null, null, null, null);
        }

        static Node constant(double value) {
            return new Node(NodeKind.CONSTANT, null,
                    value, null, null, null, null);
        }

        static Node unary(Opcode opcode, Node operand) {
            return new Node(NodeKind.OPERATION, null,
                    0.0, opcode, operand, null, null);
        }

        static Node binary(Opcode opcode, Node left, Node right) {
            return new Node(NodeKind.OPERATION, null,
                    0.0, opcode, left, right, null);
        }

        static Node clamp(Node value, Node minimum, Node maximum) {
            return new Node(NodeKind.OPERATION, null,
                    0.0, CLAMP, value, minimum, maximum);
        }

        double evaluate(double x, double y, double z) {
            if (kind == NodeKind.CONSTANT) {
                return constant;
            }

            if (kind == NodeKind.COORDINATE) {
                return switch (coordinate) {
                    case X -> x;
                    case Y -> y;
                    case Z -> z;
                };
            }

            double a = first.evaluate(x, y, z);

            return switch (opcode) {
                case NEG -> -a;
                case ABS -> Math.abs(a);

                case ADD -> a + second.evaluate(x, y, z);
                case SUB -> a - second.evaluate(x, y, z);
                case MUL -> a * second.evaluate(x, y, z);
                case DIV -> a / second.evaluate(x, y, z);
                case MIN -> Math.min(a, second.evaluate(x, y, z));
                case MAX -> Math.max(a, second.evaluate(x, y, z));

                case CLAMP -> {
                    double minimum = second.evaluate(x, y, z);
                    double maximum = third.evaluate(x, y, z);
                    yield Math.min(Math.max(a, minimum), maximum);
                }

                default -> throw new IllegalStateException(
                        "Unsupported AST operation: " + opcode);
            };
        }

        int operationCount() {
            if (kind != NodeKind.OPERATION) {
                return 0;
            }

            return 1
                    + first.operationCount()
                    + (second == null ? 0 : second.operationCount())
                    + (third == null ? 0 : third.operationCount());
        }

        void emit(List<Instruction> instructions, List<Double> constants) {
            if (kind == NodeKind.CONSTANT) {
                int constantIndex = constants.size();
                constants.add(constant);
                instructions.add(new Instruction(PUSH_CONST, constantIndex));
                return;
            }

            if (kind == NodeKind.COORDINATE) {
                Opcode coordinateOpcode = switch (coordinate) {
                    case X -> PUSH_COORD_X;
                    case Y -> PUSH_COORD_Y;
                    case Z -> PUSH_COORD_Z;
                };
                instructions.add(new Instruction(coordinateOpcode));
                return;
            }

            first.emit(instructions, constants);

            if (second != null) {
                second.emit(instructions, constants);
            }

            if (third != null) {
                third.emit(instructions, constants);
            }

            instructions.add(new Instruction(opcode));
        }
    }

    private static Node x() {
        return Node.coordinate(Coordinate.X);
    }

    private static Node y() {
        return Node.coordinate(Coordinate.Y);
    }

    private static Node z() {
        return Node.coordinate(Coordinate.Z);
    }

    private static Node c(double value) {
        return Node.constant(value);
    }

    private static Node unary(Opcode opcode, Node operand) {
        return Node.unary(opcode, operand);
    }

    private static Node binary(Opcode opcode, Node left, Node right) {
        return Node.binary(opcode, left, right);
    }

    private static Node buildArithmeticTree() {
        // The expression contains 36 supported arithmetic operations.
        // Выражение содержит 36 поддерживаемых арифметических операций.

        Node a = binary(MUL, x(), c(0.125));
        Node b = binary(MUL, y(), c(0.25));
        Node cc = binary(ADD, a, b);

        Node d = binary(MUL, z(), c(0.0625));
        Node e = binary(MUL, x(), c(0.03125));
        Node f = binary(SUB, d, e);
        Node g = binary(SUB, cc, f);
        Node h = unary(ABS, g);
        Node i = binary(ADD, h, c(0.5));

        Node j = binary(MUL, y(), c(0.015625));
        Node k = binary(ADD, j, c(1.5));
        Node l = binary(MUL, i, k);

        Node m = unary(ABS, z());
        Node n = binary(MUL, m, c(0.0078125));
        Node o = binary(ADD, n, c(1.0));
        Node p = binary(DIV, l, o);

        Node q = binary(MIN, p, c(8.0));
        Node r = binary(MAX, q, c(0.125));

        Node s = binary(MUL, x(), y());
        Node t = binary(MUL, s, c(0.00390625));
        Node u = binary(MUL, z(), c(0.001953125));
        Node v = binary(ADD, t, u);
        Node w = unary(ABS, v);

        Node aa = binary(ADD, w, c(0.25));
        Node ab = binary(MUL, aa, c(0.5));
        Node ac = binary(ADD, r, ab);

        Node ad = binary(MAX, ac, c(0.25));
        Node ae = binary(MIN, ad, c(12.0));
        Node af = Node.clamp(ae, c(0.125), c(10.0));

        Node ag = binary(SUB, af, c(0.75));
        Node ah = unary(ABS, ag);
        Node ai = binary(ADD, ah, c(0.125));

        Node aj = unary(ABS, y());
        Node ak = binary(MUL, aj, c(0.03125));
        Node al = binary(ADD, ak, c(1.0));

        return binary(DIV, ai, al);
    }

    private record ProgramBundle(ArithmeticProgram program) {
    }

    private static ProgramBundle compileArithmeticProgram(Node root) {
        List<Instruction> instructions = new ArrayList<>();
        List<Double> constants = new ArrayList<>();

        root.emit(instructions, constants);
        instructions.add(new Instruction(RETURN));

        double[] constantArray = constants.stream()
                .mapToDouble(Double::doubleValue)
                .toArray();

        ArithmeticProgram program = new ArithmeticProgram(
                instructions, constantArray);

        return new ProgramBundle(program);
    }

    private static void gateArithmetic(
            Node tree,
            ArithmeticProgram program,
            NativeArithmeticBridge bridge,
            Inputs inputs
    ) {
        int size = inputs.size();

        double[] treeOutput = new double[size];
        double[] javaOutput = new double[size];
        double[] nativeOutput = new double[size];

        objectTreeBatch(tree, inputs, treeOutput);
        optimizedJavaBatch(inputs, javaOutput);

        try (var context = bridge.createContext(program, size)) {
            context.evaluate(inputs.x, inputs.y, inputs.z, nativeOutput, size);
        }

        for (int index = 0; index < size; index++) {
            long expected = Double.doubleToRawLongBits(treeOutput[index]);
            long optimized = Double.doubleToRawLongBits(javaOutput[index]);

            if (expected != optimized) {
                throw new IllegalStateException(
                        "W2 optimized Java raw-bit mismatch"
                                + "; size=" + size
                                + "; index=" + index
                                + "; object-tree=0x" + Long.toHexString(expected)
                                + "; optimized-java=0x" + Long.toHexString(optimized));
            }

            long nativeBits = Double.doubleToRawLongBits(nativeOutput[index]);

            if (expected != nativeBits) {
                throw new IllegalStateException(
                        "W2 native raw-bit mismatch"
                                + "; size=" + size
                                + "; index=" + index
                                + "; object-tree=0x" + Long.toHexString(expected)
                                + "; native=0x" + Long.toHexString(nativeBits));
            }
        }
    }

    private static void objectTreeBatch(
            Node root,
            Inputs inputs,
            double[] output
    ) {
        for (int index = 0; index < inputs.size(); index++) {
            output[index] = root.evaluate(
                    inputs.x[index], inputs.y[index], inputs.z[index]);
        }
    }

    /**
     * Explicit arithmetic batch evaluator.
     * No temporary objects or arrays are allocated in the point loop.
     */
    private static void optimizedJavaBatch(Inputs inputs, double[] output) {
        for (int index = 0; index < inputs.size(); index++) {
            double x = inputs.x[index];
            double y = inputs.y[index];
            double z = inputs.z[index];

            double a = x * 0.125;
            double b = y * 0.25;
            double cc = a + b;

            double d = z * 0.0625;
            double e = x * 0.03125;
            double f = d - e;
            double g = cc - f;
            double h = Math.abs(g);
            double i = h + 0.5;

            double j = y * 0.015625;
            double k = j + 1.5;
            double l = i * k;

            double m = Math.abs(z);
            double n = m * 0.0078125;
            double o = n + 1.0;
            double p = l / o;

            double q = Math.min(p, 8.0);
            double r = Math.max(q, 0.125);

            double s = x * y;
            double t = s * 0.00390625;
            double u = z * 0.001953125;
            double v = t + u;
            double w = Math.abs(v);

            double aa = w + 0.25;
            double ab = aa * 0.5;
            double ac = r + ab;

            double ad = Math.max(ac, 0.25);
            double ae = Math.min(ad, 12.0);
            double af = Math.min(Math.max(ae, 0.125), 10.0);

            double ag = af - 0.75;
            double ah = Math.abs(ag);
            double ai = ah + 0.125;

            double aj = Math.abs(y);
            double ak = aj * 0.03125;
            double al = ak + 1.0;

            output[index] = ai / al;
        }
    }

    // ---------------------------------------------------------------------
    // Correctness-independent measurement infrastructure.
    // ---------------------------------------------------------------------

    private record Inputs(double[] x, double[] y, double[] z) {
        int size() {
            return x.length;
        }
    }

    @FunctionalInterface
    private interface TimedAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ChecksumAction {
        long checksum();
    }

    private record Measurement(
            String contender,
            double medianNsPerPoint,
            double minimumNsPerPoint,
            long checksum
    ) {
    }

    private static List<Measurement> measure(
            int size,
            TimedAction[] actions,
            ChecksumAction[] checksumActions
    ) throws Throwable {
        if (actions.length != checksumActions.length || actions.length == 0) {
            throw new IllegalArgumentException("Invalid measurement contenders");
        }

        for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) {
            for (TimedAction action : actions) {
                action.run();
            }
        }

        @SuppressWarnings("unchecked")
        List<Double>[] samples = new List[actions.length];

        long[] checksums = new long[actions.length];

        for (int index = 0; index < actions.length; index++) {
            samples[index] = new ArrayList<>(MEASURED_ITERATIONS);
            checksums[index] = INITIAL_CHECKSUM;
        }

        // Rotate contender order to reduce fixed-order measurement bias.
        // Порядок участников меняется для уменьшения систематического смещения.
        for (int iteration = 0; iteration < MEASURED_ITERATIONS; iteration++) {
            for (int step = 0; step < actions.length; step++) {
                int contender = (iteration + step) % actions.length;

                long start = System.nanoTime();
                actions[contender].run();
                long elapsed = System.nanoTime() - start;

                samples[contender].add((double) elapsed / size);

                // Consume all results outside the timed interval.
                checksums[contender] = Long.rotateLeft(
                        checksums[contender], 7)
                        ^ checksumActions[contender].checksum();
            }
        }

        String[] names = actions.length == 2
                ? new String[]{"Java reference", "Native scalar FFM"}
                : new String[]{"Java object tree", "Optimized Java batch",
                        "Native arithmetic batch"};

        List<Measurement> results = new ArrayList<>(actions.length);

        for (int index = 0; index < actions.length; index++) {
            double[] sorted = samples[index].stream()
                    .mapToDouble(Double::doubleValue)
                    .sorted()
                    .toArray();

            double median = sorted[sorted.length / 2];
            double minimum = sorted[0];

            results.add(new Measurement(
                    names[index], median, minimum, checksums[index]));
        }

        return results;
    }

    private static long checksum(float[] values) {
        long result = 0x9e3779b97f4a7c15L;

        for (float value : values) {
            result = Long.rotateLeft(result, 5)
                    ^ Integer.toUnsignedLong(Float.floatToRawIntBits(value));
        }

        return result;
    }

    private static long checksum(double[] values) {
        long result = 0x9e3779b97f4a7c15L;

        for (double value : values) {
            result = Long.rotateLeft(result, 5)
                    ^ Double.doubleToRawLongBits(value);
        }

        return result;
    }

    private static void appendMeasurements(
            StringBuilder report,
            int size,
            List<Measurement> measurements
    ) {
        for (Measurement measurement : measurements) {
            report.append("| ").append(size)
                    .append(" | ").append(measurement.contender())
                    .append(" | ").append(format(measurement.medianNsPerPoint()))
                    .append(" | ").append(format(measurement.minimumNsPerPoint()))
                    .append(" | `0x")
                    .append(String.format(Locale.ROOT, "%016x", measurement.checksum()))
                    .append("` |\n");

            System.out.printf(Locale.ROOT,
                    "%s size=%d median=%.3f ns/point minimum=%.3f ns/point checksum=0x%016x%n",
                    measurement.contender(), size,
                    measurement.medianNsPerPoint(),
                    measurement.minimumNsPerPoint(),
                    measurement.checksum());
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ---------------------------------------------------------------------
    // Reproducibility metadata and report output.
    // ---------------------------------------------------------------------

    private static void appendMetadata(
            StringBuilder report,
            Path perlinLibrary,
            Path arithmeticLibrary
    ) throws Exception {
        report.append("# C-Noise three-way benchmark\n\n");
        report.append("- **Date:** ").append(LocalDate.now()).append('\n');
        report.append("- **CPU:** ").append(cpuDescription()).append('\n');
        report.append("- **Logical processors visible to JVM:** ")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');
        report.append("- **OS:** ")
                .append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" (")
                .append(System.getProperty("os.arch")).append(")\n");
        report.append("- **JDK vendor:** ")
                .append(System.getProperty("java.vendor")).append('\n');
        report.append("- **JDK runtime:** ")
                .append(System.getProperty("java.runtime.version")).append('\n');
        report.append("- **Git commit:** ").append(gitCommit()).append('\n');
        report.append("- **C compiler/build:** ")
                .append(cmakeCompilerDescription()).append('\n');
        report.append("- **Perlin test library:** `")
                .append(perlinLibrary).append("`\n");
        report.append("- **Arithmetic library:** `")
                .append(arithmeticLibrary).append("`\n");
        report.append("- **Farlands debug mode:** disabled\n");
        report.append("- **JVM native access:** `ALL-UNNAMED`\n\n");

        report.append("## Methodology / Методика\n\n");
        report.append("- Inputs are generated once per workload and batch size using fixed seeds.\n");
        report.append("- All contenders reuse the same input arrays for a given workload and size.\n");
        report.append("- Native contexts, FFM handles, reference objects and output arrays are "
                + "created outside measured iterations.\n");
        report.append("- Every workload/size passes a complete raw-bit gate before any timing.\n");
        report.append("- A mismatch stops execution and is recorded; no timings are published "
                + "for a failed run.\n");
        report.append("- Timings cover complete batch evaluation and are normalized to ns/point.\n");
        report.append("- Checksums are calculated outside timed intervals.\n");
        report.append("- Measurement order rotates between contenders.\n");
        report.append("- This is a standalone microbenchmark, not JMH. Results are specific "
                + "to the recorded machine and runtime.\n\n");
    }

    private static String cpuDescription() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("windows")) {
            String command = "$p=Get-CimInstance Win32_Processor | "
                    + "Select-Object -First 1; "
                    + "\"$($p.Name); cores=$($p.NumberOfCores); "
                    + "logical=$($p.NumberOfLogicalProcessors)\"";

            try {
                Process process = new ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", command)
                        .redirectErrorStream(true)
                        .start();

                if (process.waitFor(8, TimeUnit.SECONDS)) {
                    String result = new String(
                            process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8).trim();

                    if (process.exitValue() == 0 && !result.isBlank()) {
                        return result;
                    }
                } else {
                    process.destroyForcibly();
                }
            } catch (IOException ignored) {
                // Fall back to the environment-provided processor identifier.
            }
        }

        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (identifier != null && !identifier.isBlank()) {
            return identifier;
        }

        return "Not available from operating-system environment";
    }

    private static String gitCommit() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();

        int exitCode = process.waitFor();

        if (exitCode != 0 || output.isBlank()) {
            throw new IOException("Unable to determine Git HEAD: " + output);
        }

        return output;
    }

    private static String cmakeCompilerDescription() throws IOException {
        Path root = Path.of("native", "build", "reference");
        Path cache = root.resolve("CMakeCache.txt");

        if (!Files.isRegularFile(cache)) {
            throw new IOException("CMake cache not found: " + cache.toAbsolutePath());
        }

        String compilerPath = cacheValue(cache, "CMAKE_C_COMPILER");
        String buildType = cacheValue(cache, "CMAKE_BUILD_TYPE");
        String generator = cacheValue(cache, "CMAKE_GENERATOR");
        String releaseFlags = cacheValue(cache, "CMAKE_C_FLAGS_RELEASE");

        String compilerId = "unknown";
        String compilerVersion = "unknown";

        Path cmakeFiles = root.resolve("CMakeFiles");

        if (Files.isDirectory(cmakeFiles)) {
            try (Stream<Path> paths = Files.walk(cmakeFiles, 5)) {
                Path compilerInfo = paths
                        .filter(path -> path.getFileName().toString()
                                .equals("CMakeCCompiler.cmake"))
                        .findFirst()
                        .orElse(null);

                if (compilerInfo != null) {
                    String contents = Files.readString(compilerInfo);

                    compilerId = capture(contents,
                            "set\\\\(CMAKE_C_COMPILER_ID \\"([^\\"]+)\\"\\\\)",
                            compilerId);

                    compilerVersion = capture(contents,
                            "set\\\\(CMAKE_C_COMPILER_VERSION \\"([^\\"]+)\\"\\\\)",
                            compilerVersion);
                }
            }
        }

        String strictFlags = compilerId.equals("MSVC")
                ? "/W4 /WX /fp:strict /utf-8"
                : "-Wall -Wextra -Wpedantic -Werror -fno-fast-math "
                        + "-ffp-contract=off -frounding-math";

        return compilerId + " " + compilerVersion
                + "; compiler=" + compilerPath
                + "; build=" + buildType
                + "; generator=" + generator
                + "; release flags=" + releaseFlags
                + "; strict target flags=" + strictFlags;
    }

    private static String cacheValue(Path cache, String key) throws IOException {
        for (String line : Files.readAllLines(cache)) {
            if (line.startsWith(key + ":")) {
                int separator = line.indexOf('=');
                if (separator >= 0) {
                    return line.substring(separator + 1).trim();
                }
            }
        }

        return "not recorded";
    }

    private static String capture(String text, String regex, String fallback) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static void writeReport(Path path, StringBuilder report)
            throws IOException {
        Files.createDirectories(path.getParent());

        Files.writeString(path, report.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }
}</escape>
