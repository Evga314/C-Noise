import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Instruction;
import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode;

import java.nio.file.Path;
import java.util.List;

import static com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram.Opcode.*;

/**
 * Diagnostic only: reports raw-bit differences without changing acceptance tests.
 * Run separately with -Xint, default tiering, and -Xbatch -XX:-TieredCompilation.
 */
public final class ArithmeticNaNDiagnostic {
    private static volatile long sink;

    private static final double[] VALUES = {
            0.0, -0.0, 1.0, -1.0,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.longBitsToDouble(0x7ff8000000000000L),
            Double.longBitsToDouble(0xfff8000000000000L),
            Double.longBitsToDouble(0x7ff8000000000123L),
            Double.longBitsToDouble(0xfff8000000000456L),
            Double.longBitsToDouble(0x7ff0000000000123L),
            Double.longBitsToDouble(0xfff0000000000456L)
    };

    private ArithmeticNaNDiagnostic() {
    }

    private static double reference(Opcode opcode, double x, double y) {
        return switch (opcode) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> x / y;
            case MIN -> Math.min(x, y);
            case MAX -> Math.max(x, y);
            case NEG -> -x;
            case ABS -> Math.abs(x);
            default -> throw new IllegalArgumentException("Unsupported probe opcode: " + opcode);
        };
    }

    private static ArithmeticProgram program(Opcode opcode) {
        boolean unary = opcode == NEG || opcode == ABS;
        List<Instruction> instructions = unary
                ? List.of(new Instruction(PUSH_COORD_X), new Instruction(opcode),
                        new Instruction(RETURN))
                : List.of(new Instruction(PUSH_COORD_X), new Instruction(PUSH_COORD_Y),
                        new Instruction(opcode), new Instruction(RETURN));
        return new ArithmeticProgram(instructions, new double[0]);
    }

    private static long[] capture(Opcode opcode, double[] x, double[] y) {
        long[] bits = new long[x.length];
        for (int i = 0; i < x.length; i++) {
            bits[i] = Double.doubleToRawLongBits(reference(opcode, x[i], y[i]));
        }
        return bits;
    }

    private static int compare(
            String phase, Opcode opcode, double[] x, double[] y,
            long[] expected, double[] actual) {
        int mismatches = 0;
        for (int i = 0; i < expected.length; i++) {
            long actualBits = Double.doubleToRawLongBits(actual[i]);
            if (expected[i] != actualBits) {
                if (mismatches < 4) {
                    System.out.printf(
                            "%s %s x=%016x y=%016x Java=%016x C=%016x%n",
                            phase, opcode, Double.doubleToRawLongBits(x[i]),
                            Double.doubleToRawLongBits(y[i]), expected[i], actualBits);
                }
                mismatches++;
            }
        }
        System.out.printf("%s %s: %d/%d raw-bit mismatches%n",
                phase, opcode, mismatches, expected.length);
        return mismatches;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ArithmeticNaNDiagnostic <native-library>");
        }
        int count = VALUES.length * VALUES.length;
        double[] x = new double[count];
        double[] y = new double[count];
        double[] z = new double[count];
        double[] output = new double[count];
        for (int i = 0; i < count; i++) {
            x[i] = VALUES[i / VALUES.length];
            y[i] = VALUES[i % VALUES.length];
        }

        int totalMismatches = 0;
        try (var bridge = new NativeArithmeticBridge(Path.of(args[0]))) {
            for (Opcode opcode : List.of(ADD, SUB, MUL, DIV, MIN, MAX, NEG, ABS)) {
                try (var context = bridge.createContext(program(opcode), count)) {
                    long[] cold = capture(opcode, x, y);
                    context.evaluate(x, y, z, output, count);
                    totalMismatches += compare("before-warmup", opcode, x, y, cold, output);

                    long checksum = 0;
                    for (int iteration = 0; iteration < 50_000; iteration++) {
                        int index = iteration % count;
                        checksum ^= Double.doubleToRawLongBits(
                                reference(opcode, x[index], y[index]));
                    }
                    sink = checksum;

                    long[] warm = capture(opcode, x, y);
                    context.evaluate(x, y, z, output, count);
                    totalMismatches += compare("after-warmup", opcode, x, y, warm, output);
                    int changed = 0;
                    for (int i = 0; i < count; i++) {
                        if (cold[i] != warm[i]) {
                            if (changed < 4) {
                                System.out.printf(
                                        "Java changed %s x=%016x y=%016x before=%016x after=%016x%n",
                                        opcode, Double.doubleToRawLongBits(x[i]),
                                        Double.doubleToRawLongBits(y[i]), cold[i], warm[i]);
                            }
                            changed++;
                        }
                    }
                    System.out.printf("%s: %d Java results changed after warmup%n", opcode, changed);
                }
            }
        }
        System.out.println("Diagnostic complete; total raw-bit mismatches: " + totalMismatches);
        if (totalMismatches != 0) {
            System.exit(1);
        }
    }
}
