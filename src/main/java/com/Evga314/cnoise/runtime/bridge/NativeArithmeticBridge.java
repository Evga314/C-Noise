package com.Evga314.cnoise.runtime.bridge;

import com.Evga314.cnoise.runtime.bytecode.ArithmeticProgram;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Explicit-path, thread-confined bridge for the experimental arithmetic ABI.
 *
 * Create one bridge per worker thread. Immutable ArithmeticProgram instances
 * may be shared, but bridges and execution contexts may not. No native segments
 * or handles escape this class. This bridge does not enable world generation.
 *
 * The launching JVM must grant native access to this module (ALL-UNNAMED when
 * running on the class path). Native load, linkage and status failures propagate
 * to the caller; they are never presented as successful calculations.
 */
public final class NativeArithmeticBridge implements AutoCloseable {
    private static final int ABI_VERSION = 1;

    private static final MemoryLayout INSTRUCTION = MemoryLayout.structLayout(
            JAVA_INT.withName("opcode"),
            JAVA_INT.withName("operand"));
    private static final MemoryLayout PROGRAM = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("program_version"),
            ADDRESS.withName("instructions"),
            JAVA_INT.withName("instruction_count"),
            JAVA_INT.withName("max_stack"),
            ADDRESS.withName("constants"),
            JAVA_INT.withName("constant_count"),
            JAVA_INT.withName("reserved"));
    private static final MemoryLayout BATCH = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("reserved"),
            ADDRESS.withName("x"),
            ADDRESS.withName("y"),
            ADDRESS.withName("z"),
            JAVA_LONG.withName("point_count"));
    private static final MemoryLayout BUFFER = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("reserved"),
            ADDRESS.withName("values"),
            JAVA_LONG.withName("capacity"));

    private final Thread owner = Thread.currentThread();
    private final Arena libraryArena;
    private final MethodHandle verify;
    private final MethodHandle evaluate;
    private boolean closed;

    // Explicit-path FFM loading is intentional; native access must be granted by the JVM launcher.
    @SuppressWarnings("restricted")
    public NativeArithmeticBridge(Path library) {
        Objects.requireNonNull(library, "library");
        Path absolute = library.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("Native library not found: " + absolute);
        }
        if (ADDRESS.byteSize() != Long.BYTES) {
            throw new UnsupportedOperationException("C-Noise requires a 64-bit JVM");
        }

        Arena arena = Arena.ofConfined();
        boolean initialized = false;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(absolute, arena);
            MethodHandle version = bind(linker, symbols, "cnoise_abi_version",
                    FunctionDescriptor.of(JAVA_INT));
            MethodHandle size = bind(linker, symbols, "cnoise_layout_size",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            MethodHandle alignment = bind(linker, symbols, "cnoise_layout_alignment",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT));

            int actualVersion = callInt(version);
            if (actualVersion != ABI_VERSION) {
                throw new IllegalStateException(
                        "Native ABI mismatch: expected " + ABI_VERSION + ", got " + actualVersion);
            }
            MemoryLayout[] layouts = {INSTRUCTION, PROGRAM, BATCH, BUFFER, BUFFER};
            for (int index = 0; index < layouts.length; index++) {
                int id = index + 1;
                MemoryLayout layout = layouts[index];
                int actualSize = callInt(size, id);
                int actualAlignment = callInt(alignment, id);
                if (actualSize != layout.byteSize() || actualAlignment != layout.byteAlignment()) {
                    throw new IllegalStateException("Native layout mismatch for ID " + id
                            + ": size=" + actualSize + ", alignment=" + actualAlignment);
                }
            }

            verify = bind(linker, symbols, "cnoise_verify_program",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            evaluate = bind(linker, symbols, "cnoise_eval_batch",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            libraryArena = arena;
            initialized = true;
        } finally {
            if (!initialized) {
                arena.close();
            }
        }
    }

    // Descriptors are internal constants matching cnoise.h, not caller-provided signatures.
    @SuppressWarnings("restricted")
    private static MethodHandle bind(
            Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Missing native symbol: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static int callInt(MethodHandle handle) {
        try {
            return (int) handle.invokeExact();
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    private static int callInt(MethodHandle handle, int argument) {
        try {
            return (int) handle.invokeExact(argument);
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    /*
     * MethodHandle.invokeExact declares Throwable. Preserve unchecked failures;
     * a checked exception is not part of any of these C function signatures.
     */
    private static RuntimeException invocationFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Unexpected checked exception from native downcall", failure);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Native bridge belongs to thread " + owner.getName());
        }
    }

    private void requireOpen() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("Native bridge is closed");
        }
    }

    private static void requireSuccess(String operation, int status) {
        if (status != 0) {
            throw new NativeStatusException(operation, status);
        }
    }

    public static final class NativeStatusException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int status;

        private NativeStatusException(String operation, int status) {
            super(operation + " failed: " + statusName(status) + " (" + status + ")");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static String statusName(int status) {
        return switch (status) {
            case 0 -> "OK";
            case 1 -> "NEED_JAVA";
            case 2 -> "INVALID_ARGUMENT";
            case 3 -> "INVALID_PROGRAM";
            case 4 -> "BUFFER_TOO_SMALL";
            case 5 -> "UNSUPPORTED_OPERATION";
            case 6 -> "INTERNAL_ERROR";
            case 7 -> "UNSUPPORTED_FP_ENVIRONMENT";
            default -> "UNKNOWN_STATUS";
        };
    }

    /**
     * Allocates program storage and reusable per-execution buffers once.
     * The returned context must be closed separately, preferably before its
     * bridge. Closing the bridge first disables execution but does not prevent
     * subsequent context cleanup.
     */
    public ExecutionContext createContext(ArithmeticProgram program, int capacity) {
        requireOpen();
        Objects.requireNonNull(program, "program");
        if (capacity < 1 || capacity > ArithmeticProgram.MAX_BATCH_POINTS) {
            throw new IllegalArgumentException(
                    "Capacity must be between 1 and " + ArithmeticProgram.MAX_BATCH_POINTS);
        }
        return new ExecutionContext(program, capacity);
    }

    @Override
    public void close() {
        requireOwner();
        if (!closed) {
            libraryArena.close();
            closed = true;
        }
    }

    public final class ExecutionContext implements AutoCloseable {
        private final Arena arena;
        private final int capacity;
        private final MemorySegment programView;
        private final MemorySegment batchView;
        private final MemorySegment outputView;
        private final MemorySegment workspaceView;
        private final MemorySegment xBuffer;
        private final MemorySegment yBuffer;
        private final MemorySegment zBuffer;
        private final MemorySegment resultBuffer;
        private boolean contextClosed;

        private ExecutionContext(ArithmeticProgram program, int capacity) {
            this.capacity = capacity;
            Arena storage = Arena.ofConfined();
            boolean initialized = false;
            try {
                MemorySegment instructions = storage.allocate(
                        Math.multiplyExact((long) program.instructionCount(), INSTRUCTION.byteSize()),
                        INSTRUCTION.byteAlignment());
                for (int index = 0; index < program.instructionCount(); index++) {
                    ArithmeticProgram.Instruction instruction = program.instructions().get(index);
                    long offset = Math.multiplyExact((long) index, INSTRUCTION.byteSize());
                    instructions.set(JAVA_INT, offset, instruction.opcode().nativeId());
                    instructions.set(JAVA_INT, offset + Integer.BYTES, instruction.operand());
                }

                MemorySegment constants = MemorySegment.NULL;
                if (program.constantCount() != 0) {
                    constants = storage.allocate(JAVA_DOUBLE, program.constantCount());
                    for (int index = 0; index < program.constantCount(); index++) {
                        constants.setAtIndex(JAVA_DOUBLE, index, program.constantAt(index));
                    }
                }

                programView = storage.allocate(PROGRAM);
                programView.set(JAVA_INT, 0, Math.toIntExact(PROGRAM.byteSize()));
                programView.set(JAVA_INT, 4, ArithmeticProgram.PROGRAM_VERSION);
                programView.set(ADDRESS, 8, instructions);
                programView.set(JAVA_INT, 16, program.instructionCount());
                programView.set(JAVA_INT, 20, program.maxStack());
                programView.set(ADDRESS, 24, constants);
                programView.set(JAVA_INT, 32, program.constantCount());
                programView.set(JAVA_INT, 36, 0);

                MemorySegment requiredStack = storage.allocate(JAVA_INT);
                int status;
                try {
                    status = (int) verify.invokeExact(programView, requiredStack);
                } catch (Throwable failure) {
                    throw invocationFailure(failure);
                }
                requireSuccess("cnoise_verify_program", status);
                if (requiredStack.get(JAVA_INT, 0) != program.maxStack()) {
                    throw new IllegalStateException("Java and native stack verification disagree");
                }

                xBuffer = storage.allocate(JAVA_DOUBLE, capacity);
                yBuffer = storage.allocate(JAVA_DOUBLE, capacity);
                zBuffer = storage.allocate(JAVA_DOUBLE, capacity);
                resultBuffer = storage.allocate(JAVA_DOUBLE, capacity);
                MemorySegment scratch = storage.allocate(JAVA_DOUBLE, program.maxStack());

                batchView = storage.allocate(BATCH);
                batchView.set(JAVA_INT, 0, Math.toIntExact(BATCH.byteSize()));
                batchView.set(JAVA_INT, 4, 0);
                batchView.set(ADDRESS, 8, xBuffer);
                batchView.set(ADDRESS, 16, yBuffer);
                batchView.set(ADDRESS, 24, zBuffer);
                batchView.set(JAVA_LONG, 32, 0L);

                outputView = bufferView(storage, resultBuffer, capacity);
                workspaceView = bufferView(storage, scratch, program.maxStack());
                arena = storage;
                initialized = true;
            } finally {
                if (!initialized) {
                    storage.close();
                }
            }
        }

        public int capacity() {
            return capacity;
        }

        /**
         * Evaluates the first count elements without allocating native memory.
         * Inputs must not be concurrently modified while being copied.
         *
         * Output may alias a Java input array: all inputs are copied before the
         * downcall. No result is published to output unless the native status
         * is OK. Elements beyond count remain unchanged.
         */
        public void evaluate(
                double[] x, double[] y, double[] z, double[] output, int count) {
            requireOpen();
            if (contextClosed) {
                throw new IllegalStateException("Execution context is closed");
            }
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
            Objects.requireNonNull(z, "z");
            Objects.requireNonNull(output, "output");
            if (count < 0 || count > capacity) {
                throw new IllegalArgumentException("Batch count outside context capacity: " + count);
            }
            if (x.length < count || y.length < count || z.length < count || output.length < count) {
                throw new IllegalArgumentException("Array shorter than batch count " + count);
            }

            MemorySegment.copy(x, 0, xBuffer, JAVA_DOUBLE, 0, count);
            MemorySegment.copy(y, 0, yBuffer, JAVA_DOUBLE, 0, count);
            MemorySegment.copy(z, 0, zBuffer, JAVA_DOUBLE, 0, count);
            batchView.set(JAVA_LONG, 32, (long) count);

            int status;
            try {
                status = (int) NativeArithmeticBridge.this.evaluate.invokeExact(
                        programView, batchView, outputView, workspaceView);
            } catch (Throwable failure) {
                throw invocationFailure(failure);
            }
            requireSuccess("cnoise_eval_batch", status);
            MemorySegment.copy(resultBuffer, JAVA_DOUBLE, 0, output, 0, count);
        }

        @Override
        public void close() {
            requireOwner();
            if (!contextClosed) {
                arena.close();
                contextClosed = true;
            }
        }
    }

    private static MemorySegment bufferView(Arena arena, MemorySegment values, long capacity) {
        MemorySegment view = arena.allocate(BUFFER);
        view.set(JAVA_INT, 0, Math.toIntExact(BUFFER.byteSize()));
        view.set(JAVA_INT, 4, 0);
        view.set(ADDRESS, 8, values);
        view.set(JAVA_LONG, 16, capacity);
        return view;
    }
}
