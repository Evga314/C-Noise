package com.Evga314.cnoise.runtime.bytecode;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, verified program for the initial arithmetic VM.
 * This is not yet the bytecode format for a complete Minecraft density tree.
 * Native IDs and limits must match native/include/cnoise.h.
 */
public final class ArithmeticProgram {
    public static final int PROGRAM_VERSION = 1;
    public static final int MAX_INSTRUCTIONS = 65_536;
    public static final int MAX_CONSTANTS = 65_536;
    public static final int MAX_STACK = 1_024;
    public static final int MAX_BATCH_POINTS = 65_536;

    public enum Opcode {
        PUSH_CONST(1, 0, 1),
        PUSH_COORD_X(2, 0, 1),
        PUSH_COORD_Y(3, 0, 1),
        PUSH_COORD_Z(4, 0, 1),
        ADD(5, 2, 1),
        SUB(6, 2, 1),
        MUL(7, 2, 1),
        DIV(8, 2, 1),
        MIN(9, 2, 1),
        MAX(10, 2, 1),
        NEG(11, 1, 1),
        ABS(12, 1, 1),
        CLAMP(13, 3, 1),
        RETURN(14, 1, 0);

        private final int nativeId;
        private final int consumed;
        private final int produced;

        Opcode(int nativeId, int consumed, int produced) {
            this.nativeId = nativeId;
            this.consumed = consumed;
            this.produced = produced;
        }

        public int nativeId() {
            return nativeId;
        }

        public int consumed() {
            return consumed;
        }

        public int produced() {
            return produced;
        }
    }

    /**
     * Only PUSH_CONST accepts a nonzero operand.
     * Its upper bound is checked against the owning program's constant table.
     */
    public record Instruction(Opcode opcode, int operand) {
        public Instruction {
            Objects.requireNonNull(opcode, "opcode");
            if (opcode == Opcode.PUSH_CONST) {
                if (operand < 0) {
                    throw new IllegalArgumentException("Negative constant index: " + operand);
                }
            } else if (operand != 0) {
                throw new IllegalArgumentException(opcode + " requires operand == 0");
            }
        }

        public Instruction(Opcode opcode) {
            this(opcode, 0);
        }
    }

    private final List<Instruction> instructions;
    private final double[] constants;
    private final int maxStack;

    /**
     * Takes defensive copies and validates the entire linear program.
     * Callers must not mutate either source while this constructor copies it.
     * Once constructed, the program can be shared between threads.
     */
    public ArithmeticProgram(List<Instruction> instructions, double[] constants) {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(constants, "constants");

        if (instructions.isEmpty() || instructions.size() > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException(
                    "Instruction count must be between 1 and " + MAX_INSTRUCTIONS);
        }
        if (constants.length > MAX_CONSTANTS) {
            throw new IllegalArgumentException(
                    "Constant count exceeds " + MAX_CONSTANTS);
        }

        this.instructions = List.copyOf(instructions);
        this.constants = constants.clone();
        this.maxStack = verify();
    }

    private int verify() {
        int depth = 0;
        int maximum = 0;

        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            Opcode opcode = instruction.opcode();

            if (opcode == Opcode.PUSH_CONST && instruction.operand() >= constants.length) {
                throw invalid(index, "Constant index outside table: " + instruction.operand());
            }
            if (depth < opcode.consumed()) {
                throw invalid(index, "Stack underflow for " + opcode);
            }
            if (opcode == Opcode.RETURN
                    && (index != instructions.size() - 1 || depth != 1)) {
                throw invalid(index, "RETURN must be terminal with exactly one stack value");
            }

            depth = depth - opcode.consumed() + opcode.produced();
            maximum = Math.max(maximum, depth);
            if (maximum > MAX_STACK) {
                throw invalid(index, "Stack depth exceeds " + MAX_STACK);
            }
        }

        if (instructions.getLast().opcode() != Opcode.RETURN) {
            throw invalid(instructions.size() - 1, "Missing terminal RETURN");
        }
        return maximum;
    }

    private static IllegalArgumentException invalid(int index, String reason) {
        return new IllegalArgumentException("Instruction " + index + ": " + reason);
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    public int instructionCount() {
        return instructions.size();
    }

    public int constantCount() {
        return constants.length;
    }

    public double constantAt(int index) {
        return constants[index];
    }

    public double[] copyConstants() {
        return constants.clone();
    }

    public int maxStack() {
        return maxStack;
    }
}
