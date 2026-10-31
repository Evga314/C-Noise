import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Instruction;
import com.Evga314.cnoise.runtime.verification.VerifiedArithmeticExecution;
import com.Evga314.cnoise.runtime.verification.VerifiedArithmeticExecution.State;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode.*;

/**
 * Tests the publication gate independently of native arithmetic admission.
 * Deliberately inconsistent evaluators below inject faults into the comparison;
 * they are not substitutes for an authoritative production Java evaluator.
 */
public final class VerifiedArithmeticExecutionTest {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final double SENTINEL = 987654.25;

    private VerifiedArithmeticExecutionTest() {
    }

    private static ArithmeticProgram identity() {
        return new ArithmeticProgram(
                List.of(new Instruction(PUSH_COORD_X), new Instruction(RETURN)),
                new double[0]);
    }

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void bits(double expected, double actual, String message) {
        check(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
                message + ": expected " + Long.toHexString(Double.doubleToRawLongBits(expected))
                        + ", actual " + Long.toHexString(Double.doubleToRawLongBits(actual)));
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        CHECKS.incrementAndGet();
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Unexpected exception", failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static void copy(
            double[] x, double[] y, double[] z, double[] output, int count) {
        System.arraycopy(x, 0, output, 0, count);
    }

    private static void testMatchingAndValidation(Path library) {
        AtomicInteger calls = new AtomicInteger();
        try (var bridge = new NativeArithmeticBridge(library);
             var gate = new VerifiedArithmeticExecution(
                     bridge.createContext(identity(), 6),
                     (x, y, z, output, count) -> {
                         calls.incrementAndGet();
                         copy(x, y, z, output, count);
                     }, "identity", "test-world")) {
            double[] x = {
                    -0.0, Double.MIN_VALUE, Double.POSITIVE_INFINITY,
                    Double.longBitsToDouble(0xfff8000000000456L), 7.0, -8.0
            };
            double[] output = new double[6];
            Arrays.fill(output, SENTINEL);

            check(gate.capacity() == 6, "Capacity");
            gate.evaluate(x, x, x, output, 0);
            check(calls.get() == 0, "Empty batch does not call Java");
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(x, x, x, output, -1));
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(x, x, x, output, 7));
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(new double[0], x, x, output, 1));
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(x, new double[0], x, output, 1));
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(x, x, new double[0], output, 1));
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(x, x, x, new double[0], 1));
            expect(NullPointerException.class,
                    () -> gate.evaluate(null, x, x, output, 1));
            check(calls.get() == 0, "Invalid arguments do not call Java");
            for (double value : output) {
                bits(SENTINEL, value, "Validation does not publish");
            }

            for (int iteration = 0; iteration < 20; iteration++) {
                gate.evaluate(x, x, x, output, 4);
                for (int i = 0; i < 4; i++) {
                    bits(x[i], output[i], "Identity raw bits");
                }
                bits(SENTINEL, output[4], "Unwritten tail");
                bits(SENTINEL, output[5], "Unwritten tail");
            }
            check(calls.get() == 20, "Exactly one Java evaluation per batch");
            check(gate.state() == State.SYNCHRONOUS_VERIFICATION,
                    "Success does not promote to unchecked native execution");
            check(gate.firstMismatch().isEmpty(), "No false mismatch");

            double[] before = x.clone();
            gate.evaluate(x, x, x, x, x.length);
            for (int i = 0; i < x.length; i++) {
                bits(before[i], x[i], "Aliased matching output");
            }
        }
    }

    private static void testRealAddition(Path library) {
        double[] values = {
                0.0, -0.0, Double.MIN_VALUE, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff8000000000123L),
                Double.longBitsToDouble(0xfff8000000000456L),
                Double.longBitsToDouble(0x7ff0000000000123L)
        };
        int count = values.length * values.length;
        double[] x = new double[count];
        double[] y = new double[count];
        double[] z = new double[count];
        double[] output = new double[count + 1];
        double[] nativeOutput = new double[count];
        long[] capturedBits = new long[count];
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            x[i] = values[i / values.length];
            y[i] = values[i % values.length];
        }
        Arrays.fill(output, SENTINEL);
        ArithmeticProgram addition = new ArithmeticProgram(
                List.of(new Instruction(PUSH_COORD_X), new Instruction(PUSH_COORD_Y),
                        new Instruction(ADD), new Instruction(RETURN)), new double[0]);
        try (var bridge = new NativeArithmeticBridge(library);
             var probe = bridge.createContext(addition, count);
             var gate = new VerifiedArithmeticExecution(
                     bridge.createContext(addition, count),
                     (xs, ys, zs, results, size) -> {
                         calls.incrementAndGet();
                         for (int i = 0; i < size; i++) {
                             results[i] = xs[i] + ys[i];
                             // Capture this evaluation, not a second potentially recompiled one.
                             capturedBits[i] = Double.doubleToRawLongBits(results[i]);
                         }
                     }, "real-addition", "test-world")) {
            VerifiedArithmeticExecution.Mismatch first = null;
            for (int batch = 1; batch <= 512; batch++) {
                probe.evaluate(x, y, z, nativeOutput, count);
                gate.evaluate(x, y, z, output, count);
                check(calls.get() == batch, "Real addition runs Java once per batch");
                int differingIndex = -1;
                for (int i = 0; i < count; i++) {
                    check(Double.doubleToRawLongBits(output[i]) == capturedBits[i],
                            "Real addition publishes captured Java bits at " + i);
                    if (differingIndex < 0
                            && Double.doubleToRawLongBits(nativeOutput[i]) != capturedBits[i]) {
                        differingIndex = i;
                    }
                }
                bits(SENTINEL, output[count], "Real addition preserves output tail");
                if (first == null && differingIndex >= 0) {
                    first = gate.firstMismatch().orElseThrow();
                    check(first.batchNumber() == batch, "Real mismatch batch");
                    check(first.pointIndex() == differingIndex, "Real first mismatch index");
                    check(first.expectedBits() == capturedBits[differingIndex],
                            "Real mismatch captures this Java evaluation");
                    check(first.actualBits()
                                    == Double.doubleToRawLongBits(nativeOutput[differingIndex]),
                            "Real mismatch captures native bits");
                    check(first.xBits() == Double.doubleToRawLongBits(x[differingIndex]),
                            "Real mismatch X bits");
                    check(first.yBits() == Double.doubleToRawLongBits(y[differingIndex]),
                            "Real mismatch Y bits");
                    check(first.componentId().equals("real-addition"), "Real component ID");
                    check(first.worldSignature().equals("test-world"), "Real world ID");
                }
                if (first == null) {
                    check(gate.state() == State.SYNCHRONOUS_VERIFICATION,
                            "Matching real batches remain checked");
                    check(gate.firstMismatch().isEmpty(), "No invented real mismatch");
                } else {
                    check(gate.state() == State.JAVA_ONLY, "Real mismatch latches Java-only");
                    check(gate.firstMismatch().orElseThrow().equals(first),
                            "Real first diagnostic survives subsequent batches and warmup");
                }
            }
            System.out.println("Real addition: 512 batches checked; first mismatch: "
                    + (first == null ? "none on this runtime" : first));
        }
    }

    private static void testMismatchAndLatch(Path library, double actual, double expected) {
        AtomicInteger calls = new AtomicInteger();
        NativeArithmeticBridge bridge = new NativeArithmeticBridge(library);
        var context = bridge.createContext(identity(), 4);
        var gate = new VerifiedArithmeticExecution(
                context,
                (x, y, z, output, count) -> {
                    calls.incrementAndGet();
                    copy(x, y, z, output, count);
                    // Fault injection: disagreement at index 1 and later in the batch.
                    if (count > 1) {
                        output[1] = expected;
                    }
                    if (count > 2) {
                        output[2] = 77.0;
                    }
                }, "fault-injection", "test-world");
        try {
            double[] x = {3.0, actual, 5.0, SENTINEL};
            double[] y = {10.0, 11.0, 12.0, 13.0};
            double[] z = {-1.0, -2.0, -3.0, -4.0};
            Instant start = Instant.now();

            gate.evaluate(x, y, z, x, 3);
            bits(3.0, x[0], "Java prefix");
            bits(expected, x[1], "Java mismatching value");
            bits(77.0, x[2], "Entire Java batch, not mixed native suffix");
            bits(SENTINEL, x[3], "Aliased output tail");
            check(calls.get() == 1, "Mismatch does not repeat Java computation");
            check(gate.state() == State.JAVA_ONLY, "Mismatch latches Java-only");

            var mismatch = gate.firstMismatch().orElseThrow();
            check(mismatch.componentId().equals("fault-injection"), "Component identity");
            check(mismatch.worldSignature().equals("test-world"), "World identity");
            check(mismatch.batchNumber() == 1, "Batch number");
            check(mismatch.pointIndex() == 1, "First mismatching point");
            check(mismatch.expectedBits() == Double.doubleToRawLongBits(expected),
                    "Expected raw bits retained");
            check(mismatch.actualBits() == Double.doubleToRawLongBits(actual),
                    "Actual raw bits retained");
            check(mismatch.xBits() == Double.doubleToRawLongBits(actual),
                    "Coordinate captured before aliased publication");
            check(mismatch.yBits() == Double.doubleToRawLongBits(y[1]), "Y coordinate");
            check(mismatch.zBits() == Double.doubleToRawLongBits(z[1]), "Z coordinate");
            check(!mismatch.occurredAt().isBefore(start)
                    && !mismatch.occurredAt().isAfter(Instant.now()), "Timestamp");

            // Closing the independently owned bridge makes any further native call fail.
            bridge.close();
            x[0] = 19.0;
            gate.evaluate(x, y, z, x, 3);
            bits(19.0, x[0], "Java-only batch after bridge closure");
            check(calls.get() == 2, "Exactly one Java-only evaluation");
            check(gate.firstMismatch().orElseThrow().equals(mismatch),
                    "First diagnostic is immutable");
            gate.close();
            gate.close();
            check(gate.firstMismatch().orElseThrow().equals(mismatch),
                    "Diagnostic survives close");
            expect(IllegalStateException.class, () -> gate.evaluate(x, y, z, x, 1));
        } finally {
            gate.close();
            bridge.close();
        }
    }

    private static void testFailures(Path library) {
        try (var bridge = new NativeArithmeticBridge(library);
             var gate = new VerifiedArithmeticExecution(
                     bridge.createContext(identity(), 2),
                     (x, y, z, output, count) -> {
                         output[0] = 123.0;
                         throw new IllegalArgumentException("Injected evaluator failure");
                     }, "java-failure", "test-world")) {
            double[] values = {SENTINEL, SENTINEL};
            expect(IllegalArgumentException.class,
                    () -> gate.evaluate(values, values, values, values, 2));
            for (double value : values) {
                bits(SENTINEL, value, "Partial Java computation is not published");
            }
            check(gate.firstMismatch().isEmpty(), "Java exception is not a mismatch");
        }

        NativeArithmeticBridge bridge = new NativeArithmeticBridge(library);
        try (var gate = new VerifiedArithmeticExecution(
                bridge.createContext(identity(), 2),
                VerifiedArithmeticExecutionTest::copy, "native-failure", "test-world")) {
            bridge.close();
            double[] input = {1.0, 2.0};
            double[] output = {SENTINEL, SENTINEL};
            expect(IllegalStateException.class,
                    () -> gate.evaluate(input, input, input, output, 2));
            for (double value : output) {
                bits(SENTINEL, value, "Native failure does not publish Java success");
            }
            check(gate.firstMismatch().isEmpty(), "Native exception is not a mismatch");
            check(gate.state() == State.SYNCHRONOUS_VERIFICATION,
                    "Native API error is not silently latched as numerical mismatch");
        } finally {
            bridge.close();
        }
    }

    private static void testOwnership(Path library) throws Exception {
        try (var bridge = new NativeArithmeticBridge(library)) {
            var context = bridge.createContext(identity(), 1);
            try {
                expect(IllegalArgumentException.class,
                        () -> new VerifiedArithmeticExecution(
                                context, VerifiedArithmeticExecutionTest::copy, " ", "world"));
                double[] value = {1.0};
                context.evaluate(value, value, value, value, 1);
                bits(1.0, value[0], "Failed construction leaves context usable");

                try (var gate = new VerifiedArithmeticExecution(
                        context, VerifiedArithmeticExecutionTest::copy, "thread", "world")) {
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> {
                            expect(IllegalStateException.class,
                                    () -> gate.evaluate(value, value, value, value, 1));
                            expect(IllegalStateException.class, gate::close);
                            expect(IllegalStateException.class, () -> gate.state());
                            expect(IllegalStateException.class, () -> gate.firstMismatch());
                            expect(IllegalStateException.class, () -> gate.capacity());
                        }).get();
                    }
                    gate.evaluate(value, value, value, value, 1);
                    bits(1.0, value[0], "Wrong-thread attempts preserve resources");
                }
                expect(IllegalStateException.class,
                        () -> context.evaluate(value, value, value, value, 1));
            } finally {
                context.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: VerifiedArithmeticExecutionTest <native-library>");
        }
        Path library = Path.of(args[0]).toAbsolutePath();
        testMatchingAndValidation(library);
        testRealAddition(library);
        testMismatchAndLatch(library, 0.0, -0.0);
        testMismatchAndLatch(library,
                Double.longBitsToDouble(0x7ff8000000000123L),
                Double.longBitsToDouble(0x7ff8000000000456L));
        testMismatchAndLatch(library,
                Double.longBitsToDouble(0x7ff8000000000123L),
                Double.longBitsToDouble(0xfff8000000000123L));
        testFailures(library);
        testOwnership(library);
        System.out.println("Verified arithmetic execution: " + CHECKS.get() + " checks passed");
    }
}
