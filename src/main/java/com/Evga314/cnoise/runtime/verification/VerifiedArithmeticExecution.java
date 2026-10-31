package com.Evga314.cnoise.runtime.verification;

import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge.ExecutionContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous parity gate for one arithmetic execution context.
 *
 * This is not an admission certificate for world generation: unit vectors,
 * golden chunks, and Minecraft integration remain separate responsibilities.
 * Every native batch is checked; this class never promotes itself to unchecked
 * execution based on a successful warmup.
 *
 * The supplied Java evaluator must invoke the authoritative implementation.
 * It must be safe to evaluate once per batch, leave inputs unchanged, fill all
 * requested results, and neither retain nor concurrently access the arrays.
 *
 * Instances are confined to their constructing thread. Inputs must remain
 * unchanged for the duration of evaluate. A successfully constructed instance
 * owns the native context, which must not subsequently be used independently.
 */
public final class VerifiedArithmeticExecution implements AutoCloseable {
    @FunctionalInterface
    public interface JavaBatchEvaluator {
        void evaluate(double[] x, double[] y, double[] z, double[] output, int count);
    }

    public enum State {
        SYNCHRONOUS_VERIFICATION,
        JAVA_ONLY
    }

    /**
     * Raw bits preserve signed zero and NaN payloads in diagnostic data.
     * Identifiers must describe the caller's actual component and world;
     * this arithmetic layer does not invent Minecraft metadata.
     */
    public record Mismatch(
            String componentId,
            String worldSignature,
            Instant occurredAt,
            long batchNumber,
            int pointIndex,
            long xBits,
            long yBits,
            long zBits,
            long expectedBits,
            long actualBits) {
        public Mismatch {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(worldSignature, "worldSignature");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ExecutionContext nativeContext;
    private final JavaBatchEvaluator javaEvaluator;
    private final String componentId;
    private final String worldSignature;
    private final double[] javaResults;
    private final double[] nativeResults;

    private State state = State.SYNCHRONOUS_VERIFICATION;
    private Mismatch mismatch;
    private long batchNumber;
    private boolean closed;

    /**
     * Ownership transfers only if this constructor completes successfully.
     * The native context must belong to the constructing thread.
     */
    public VerifiedArithmeticExecution(
            ExecutionContext nativeContext,
            JavaBatchEvaluator javaEvaluator,
            String componentId,
            String worldSignature) {
        this.nativeContext = Objects.requireNonNull(nativeContext, "nativeContext");
        this.javaEvaluator = Objects.requireNonNull(javaEvaluator, "javaEvaluator");
        this.componentId = requireIdentifier(componentId, "componentId");
        this.worldSignature = requireIdentifier(worldSignature, "worldSignature");
        javaResults = new double[nativeContext.capacity()];
        nativeResults = new double[nativeContext.capacity()];
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "Verified arithmetic execution belongs to thread " + owner.getName());
        }
    }

    private void requireOpen() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("Verified arithmetic execution is closed");
        }
    }

    public State state() {
        requireOwner();
        return state;
    }

    /**
     * The first mismatch remains available after close.
     */
    public Optional<Mismatch> firstMismatch() {
        requireOwner();
        return Optional.ofNullable(mismatch);
    }

    public int capacity() {
        requireOwner();
        return javaResults.length;
    }

    /**
     * Publishes only a fully checked batch or an authoritative Java batch.
     * The output may alias an input; elements beyond count remain unchanged.
     *
     * On the first raw-bit mismatch, the complete Java batch is published and
     * subsequent calls no longer invoke native code. The Java evaluator is
     * not called a second time to recover the mismatching batch.
     *
     * Invalid arguments, Java evaluator failures, and native API failures
     * propagate without publishing any output. They are not reclassified as
     * numerical mismatches or silently converted into successful execution.
     */
    public void evaluate(
            double[] x, double[] y, double[] z, double[] output, int count) {
        requireOpen();
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        Objects.requireNonNull(output, "output");
        if (count < 0 || count > javaResults.length) {
            throw new IllegalArgumentException("Batch count outside context capacity: " + count);
        }
        if (x.length < count || y.length < count || z.length < count || output.length < count) {
            throw new IllegalArgumentException("Array shorter than batch count " + count);
        }
        if (count == 0) {
            return;
        }

        batchNumber = Math.incrementExact(batchNumber);
        javaEvaluator.evaluate(x, y, z, javaResults, count);

        if (state == State.JAVA_ONLY) {
            System.arraycopy(javaResults, 0, output, 0, count);
            return;
        }

        nativeContext.evaluate(x, y, z, nativeResults, count);
        for (int index = 0; index < count; index++) {
            long expected = Double.doubleToRawLongBits(javaResults[index]);
            long actual = Double.doubleToRawLongBits(nativeResults[index]);
            if (expected != actual) {
                mismatch = new Mismatch(
                        componentId,
                        worldSignature,
                        Instant.now(),
                        batchNumber,
                        index,
                        Double.doubleToRawLongBits(x[index]),
                        Double.doubleToRawLongBits(y[index]),
                        Double.doubleToRawLongBits(z[index]),
                        expected,
                        actual);
                state = State.JAVA_ONLY;
                System.arraycopy(javaResults, 0, output, 0, count);
                return;
            }
        }
        System.arraycopy(nativeResults, 0, output, 0, count);
    }

    /**
     * Releases the owned context, but not its independently owned FFM bridge.
     */
    @Override
    public void close() {
        requireOwner();
        if (!closed) {
            nativeContext.close();
            closed = true;
        }
    }
}
