import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Instruction;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode.*;

/**
 * Standalone integration suite: no game bootstrap or third-party test framework.
 * Requires Java 25 and --enable-native-access=ALL-UNNAMED.
 * The sole argument is the absolute path to the native library under test.
 */
public final class ArithmeticBridgeTest {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final double SENTINEL = 1234567.25;
    private static final double[] EDGES = {
            0.0, -0.0, 1.0, -1.0, 0.5, -0.5,
            Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.MIN_NORMAL, -Double.MIN_NORMAL,
            Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NaN, Double.longBitsToDouble(0xfff8000000000000L)
    };

    private ArithmeticBridgeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ArithmeticBridgeTest <native-library>");
        }
        Path library = Path.of(args[0]).toAbsolutePath();
        testVerifier();
        try (NativeArithmeticBridge bridge = new NativeArithmeticBridge(library)) {
            testOperations(bridge);
            testBuffers(bridge);
        }
        testLifetime(library);
        testConcurrency(library);
        System.out.println("Arithmetic FFM integration: " + CHECKS.get() + " checks passed");
    }

    private static Instruction op(Opcode opcode) {
        return new Instruction(opcode);
    }

    private static ArithmeticProgram program(Opcode... opcodes) {
        return new ArithmeticProgram(
                Arrays.stream(opcodes).map(ArithmeticBridgeTest::op).toList(), new double[0]);
    }

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void bits(double expected, double actual, String label) {
        long expectedBits = Double.doubleToRawLongBits(expected);
        long actualBits = Double.doubleToRawLongBits(actual);
        check(expectedBits == actualBits, label + ": expected 0x"
                + Long.toHexString(expectedBits) + ", got 0x" + Long.toHexString(actualBits));
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        CHECKS.incrementAndGet();
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + type.getName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getName() + ", but action succeeded");
    }

    private static void testVerifier() {
        expect(IllegalArgumentException.class, () -> program());
        expect(IllegalArgumentException.class, () -> program(ADD, RETURN));
        expect(IllegalArgumentException.class, () -> program(PUSH_COORD_X));
        expect(IllegalArgumentException.class, () -> program(PUSH_COORD_X, RETURN, RETURN));
        expect(IllegalArgumentException.class,
                () -> program(PUSH_COORD_X, PUSH_COORD_Y, RETURN));
        expect(IllegalArgumentException.class, () -> new Instruction(ADD, 1));
        expect(IllegalArgumentException.class, () -> new Instruction(PUSH_CONST, -1));
        expect(NullPointerException.class, () -> new Instruction(null));
        expect(IllegalArgumentException.class,
                () -> new ArithmeticProgram(List.of(op(PUSH_CONST), op(RETURN)), new double[0]));
        expect(IllegalArgumentException.class,
                () -> new ArithmeticProgram(List.of(op(PUSH_COORD_X), op(RETURN)),
                        new double[ArithmeticProgram.MAX_CONSTANTS + 1]));

        List<Instruction> stack = new ArrayList<>();
        for (int i = 0; i < ArithmeticProgram.MAX_STACK; i++) {
            stack.add(op(PUSH_COORD_X));
        }
        for (int i = 1; i < ArithmeticProgram.MAX_STACK; i++) {
            stack.add(op(ADD));
        }
        stack.add(op(RETURN));
        check(new ArithmeticProgram(stack, new double[0]).maxStack()
                == ArithmeticProgram.MAX_STACK, "Exact stack limit accepted");
        stack.addFirst(op(PUSH_COORD_X));
        expect(IllegalArgumentException.class, () -> new ArithmeticProgram(stack, new double[0]));

        List<Instruction> tooLong = new ArrayList<>();
        for (int i = 0; i <= ArithmeticProgram.MAX_INSTRUCTIONS; i++) {
            tooLong.add(op(PUSH_COORD_X));
        }
        expect(IllegalArgumentException.class,
                () -> new ArithmeticProgram(tooLong, new double[0]));

        double[] constants = {42.0};
        List<Instruction> source = new ArrayList<>(List.of(op(PUSH_CONST), op(RETURN)));
        ArithmeticProgram immutable = new ArithmeticProgram(source, constants);
        constants[0] = -1.0;
        source.clear();
        immutable.copyConstants()[0] = -2.0;
        bits(42.0, immutable.constantAt(0), "Defensive constant copies");
        check(immutable.instructionCount() == 2, "Defensive instruction copy");
        expect(UnsupportedOperationException.class, () -> immutable.instructions().clear());
    }

    private static double referenceBinary(Opcode opcode, double a, double b) {
        return switch (opcode) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> a / b;
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
            default -> throw new AssertionError("Not binary: " + opcode);
        };
    }

    private static void testOperations(NativeArithmeticBridge bridge) {
        int edgePairs = EDGES.length * EDGES.length;
        int count = edgePairs + 4096;
        double[] x = new double[count];
        double[] y = new double[count];
        double[] z = new double[count];
        double[] output = new double[count];
        Random random = new Random(0x434e4f495345L);
        for (int i = 0; i < count; i++) {
            if (i < edgePairs) {
                x[i] = EDGES[i / EDGES.length];
                y[i] = EDGES[i % EDGES.length];
                z[i] = EDGES[(i + 5) % EDGES.length];
            } else {
                x[i] = Math.scalb(random.nextDouble() * 2.0 - 1.0, random.nextInt(2046) - 1022);
                y[i] = Math.scalb(random.nextDouble() * 2.0 - 1.0, random.nextInt(2046) - 1022);
                z[i] = Math.scalb(random.nextDouble() * 2.0 - 1.0, random.nextInt(2046) - 1022);
            }
        }

        for (Opcode opcode : List.of(ADD, SUB, MUL, DIV, MIN, MAX)) {
            try (var context = bridge.createContext(
                    program(PUSH_COORD_X, PUSH_COORD_Y, opcode, RETURN), count)) {
                context.evaluate(x, y, z, output, count);
                for (int i = 0; i < count; i++) {
                    bits(referenceBinary(opcode, x[i], y[i]), output[i], opcode + " at " + i);
                }
            }
        }
        for (Opcode opcode : List.of(NEG, ABS)) {
            try (var context = bridge.createContext(program(PUSH_COORD_X, opcode, RETURN), count)) {
                context.evaluate(x, y, z, output, count);
                for (int i = 0; i < count; i++) {
                    double expected = opcode == NEG ? -x[i] : Math.abs(x[i]);
                    bits(expected, output[i], opcode + " at " + i);
                }
            }
        }
        try (var context = bridge.createContext(
                program(PUSH_COORD_X, PUSH_COORD_Y, PUSH_COORD_Z, CLAMP, RETURN), count)) {
            context.evaluate(x, y, z, output, count);
            for (int i = 0; i < count; i++) {
                bits(Math.min(Math.max(x[i], y[i]), z[i]), output[i], "CLAMP at " + i);
            }
        }

        ArithmeticProgram constant = new ArithmeticProgram(
                List.of(new Instruction(PUSH_CONST, 1), op(RETURN)), new double[]{1.0, -0.0});
        try (var context = bridge.createContext(constant, count)) {
            context.evaluate(x, y, z, output, count);
            for (double value : output) {
                bits(-0.0, value, "Constant signed zero");
            }
        }
    }

    private static void testBuffers(NativeArithmeticBridge bridge) {
        ArithmeticProgram sum = program(PUSH_COORD_X, PUSH_COORD_Y, ADD, PUSH_COORD_Z, SUB, RETURN);
        expect(IllegalArgumentException.class, () -> bridge.createContext(sum, 0));
        expect(IllegalArgumentException.class,
                () -> bridge.createContext(sum, ArithmeticProgram.MAX_BATCH_POINTS + 1));
        try (var context = bridge.createContext(sum, 4)) {
            double[] x = {1, 2, 3, 4};
            double[] y = {10, 20, 30, 40};
            double[] z = {2, 3, 4, 5};
            double[] output = {SENTINEL, SENTINEL, SENTINEL, SENTINEL};
            check(context.capacity() == 4, "Capacity");
            context.evaluate(x, y, z, output, 0);
            for (double value : output) {
                bits(SENTINEL, value, "Empty batch leaves output untouched");
            }
            expect(IllegalArgumentException.class, () -> context.evaluate(x, y, z, output, -1));
            expect(IllegalArgumentException.class, () -> context.evaluate(x, y, z, output, 5));
            expect(IllegalArgumentException.class,
                    () -> context.evaluate(new double[0], y, z, output, 1));
            expect(NullPointerException.class, () -> context.evaluate(null, y, z, output, 1));
            for (double value : output) {
                bits(SENTINEL, value, "Invalid input leaves output untouched");
            }
            for (int iteration = 0; iteration < 100; iteration++) {
                x[0] = iteration;
                context.evaluate(x, y, z, output, 2);
                bits(iteration + 8.0, output[0], "Reused input");
                bits(19.0, output[1], "Reused output");
                bits(SENTINEL, output[2], "Output tail");
                bits(SENTINEL, output[3], "Output tail");
            }
            context.evaluate(x, y, z, x, 4);
            bits(107.0, x[0], "Aliased Java output");
            bits(39.0, x[3], "Aliased Java output tail");
        }
        try (var context = bridge.createContext(
                program(PUSH_COORD_X, RETURN), ArithmeticProgram.MAX_BATCH_POINTS)) {
            double[] values = new double[ArithmeticProgram.MAX_BATCH_POINTS];
            Arrays.fill(values, -0.0);
            context.evaluate(values, values, values, values, values.length);
            for (double value : values) {
                bits(-0.0, value, "Maximum batch");
            }
        }
    }

    private static void testLifetime(Path library) throws Exception {
        ArithmeticProgram identity = program(PUSH_COORD_X, RETURN);
        NativeArithmeticBridge bridge = new NativeArithmeticBridge(library);
        var context = bridge.createContext(identity, 1);
        double[] value = {1.0};
        try {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    expect(IllegalStateException.class,
                            () -> context.evaluate(value, value, value, value, 1));
                    expect(IllegalStateException.class, context::close);
                    expect(IllegalStateException.class, bridge::close);
                    expect(IllegalStateException.class, () -> bridge.createContext(identity, 1));
                }).get();
            }
            context.evaluate(value, value, value, value, 1);
            bits(1.0, value[0], "Wrong-thread attempts preserve owner resources");
            context.close();
            context.close();
            expect(IllegalStateException.class,
                    () -> context.evaluate(value, value, value, value, 1));
            var orphan = bridge.createContext(identity, 1);
            try {
                bridge.close();
                bridge.close();
                expect(IllegalStateException.class,
                        () -> orphan.evaluate(value, value, value, value, 1));
                expect(IllegalStateException.class, () -> bridge.createContext(identity, 1));
            } finally {
                orphan.close();
            }
        } finally {
            context.close();
            bridge.close();
        }
    }

    private static void testConcurrency(Path library) throws Exception {
        ArithmeticProgram shared = program(PUSH_COORD_X, PUSH_COORD_Y, MUL, PUSH_COORD_Z, ADD, RETURN);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                final int workerId = worker;
                futures.add(executor.submit(() -> {
                    try (var bridge = new NativeArithmeticBridge(library);
                         var context = bridge.createContext(shared, 128)) {
                        double[] x = new double[128];
                        double[] y = new double[128];
                        double[] z = new double[128];
                        double[] output = new double[128];
                        for (int iteration = 0; iteration < 100; iteration++) {
                            for (int i = 0; i < x.length; i++) {
                                x[i] = workerId + i * 0.25;
                                y[i] = iteration - i * 0.5;
                                z[i] = -i;
                            }
                            context.evaluate(x, y, z, output, x.length);
                            for (int i = 0; i < output.length; i++) {
                                bits(x[i] * y[i] + z[i], output[i], "Concurrent worker " + workerId);
                            }
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }
}
