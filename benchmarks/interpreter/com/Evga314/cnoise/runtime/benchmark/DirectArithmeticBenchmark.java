package com.Evga314.cnoise.runtime.benchmark;

import com.Evga314.cnoise.runtime.bridge.NativeArithmeticBridge;
import com.Evga314.cnoise.runtime.benchmark.ThreeWayBenchmark.Inputs;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Direct C versus the existing Java formula and scalar native VM.
 * Прямой C против существующей Java-формулы и скалярной нативной VM.
 *
 * This is a finite-corpus experiment, not a production admission gate.
 * Это эксперимент на конечном корпусе, а не допуск к генерации мира.
 */
public final class DirectArithmeticBenchmark {
    private static final int[] SIZES = {256, 4096, 32768, 65536};
    private static final int SAMPLES = 31;
    private static final long WARMUP_NS = 1_000_000_000L;
    private static final String[] NAMES = {
            "Java specialized batch",
            "Native scalar VM + copies",
            "Direct C + copies",
            "Direct C prepared buffers"
    };
    private static volatile long blackhole;

    private DirectArithmeticBenchmark() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected: <direct DLL> <VM DLL> <new report.md>");
        }
        Path directLibrary = Path.of(args[0]).toAbsolutePath();
        Path vmLibrary = Path.of(args[1]).toAbsolutePath();
        Path reportPath = Path.of(args[2]).toAbsolutePath();
        Path failurePath = reportPath.resolveSibling(
                reportPath.getFileName() + ".failure.txt");
        if (Files.exists(reportPath) || Files.exists(failurePath)) {
            throw new IOException("Refusing to overwrite existing run: " + reportPath);
        }
        Files.createDirectories(reportPath.getParent());

        try {
            StringBuilder report = metadata(directLibrary, vmLibrary);
            execute(directLibrary, vmLibrary, report);
            report.append("\nFinal accumulated checksum: `0x")
                    .append(Long.toHexString(blackhole)).append("`.\n");
            report.append("\nPASS: all initial, post-warmup and post-measurement "
                    + "raw-bit checks passed. No production-admission claim.\n");
            Files.writeString(reportPath, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            System.out.println("Final checksum: 0x" + Long.toHexString(blackhole));
            System.out.println("Report: " + reportPath);
        } catch (Throwable failure) {
            // Preserve the original failure; never publish partial timings.
            // Сохраняем исходную ошибку; не публикуем частичные timings.
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            try {
                Files.writeString(failurePath,
                        OffsetDateTime.now() + "\n" + trace,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException loggingFailure) {
                failure.addSuppressed(loggingFailure);
            }
            throw failure;
        }
    }

    private static void execute(
            Path directLibrary, Path vmLibrary, StringBuilder report) throws Throwable {
        try (Arena arena = Arena.ofConfined();
             NativeArithmeticBridge vm = new NativeArithmeticBridge(vmLibrary)) {
            SymbolLookup symbols = SymbolLookup.libraryLookup(directLibrary, arena);
            MethodHandle direct = Linker.nativeLinker().downcallHandle(
                    symbols.find("cnoise_benchmark_direct_batch")
                            .orElseThrow(() -> new UnsatisfiedLinkError(
                                    "Missing cnoise_benchmark_direct_batch")),
                    FunctionDescriptor.of(JAVA_INT,
                            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

            checkNativeStatusContract(direct);
            List<BatchCase> cases = new ArrayList<>();
            try {
                for (int size : SIZES) {
                    BatchCase batch = new BatchCase(arena, vm, direct, size);
                    cases.add(batch);
                    batch.gate("initial");
                }
                report.append("\n## Correctness\n\n")
                        .append("All four batch sizes passed initial raw-bit gates "
                                + "before any timing. Java uses the unchanged "
                                + "`ThreeWayBenchmark.optimizedJavaBatch`; inputs "
                                + "come from its unchanged fixed-seed generator, "
                                + "including finite signed-zero/subnormal cases. "
                                + "This corpus does not establish NaN payload parity.\n");

                report.append("\n## Measurements\n\n")
                        .append("| Points | Contender | Median ns/point | Minimum ns/point "
                                + "| P90 ns/point | Repeats/sample | Checksum |\n")
                        .append("|---:|---|---:|---:|---:|---:|---|\n");
                for (BatchCase batch : cases) {
                    batch.warmup();
                    batch.gate("post-warmup");
                    batch.measure(report);
                    batch.gate("post-measurement");
                }
            } finally {
                for (BatchCase batch : cases) {
                    batch.close();
                }
            }
        }
    }

    private static void checkNativeStatusContract(MethodHandle direct) throws Throwable {
        int empty = (int) direct.invokeExact(
                MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, 0L);
        int invalid = (int) direct.invokeExact(
                MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, 1L);
        if (empty != 0 || invalid != 1) {
            throw new IllegalStateException(
                    "Direct C status contract: empty=" + empty + ", null=" + invalid);
        }
    }

    private static final class BatchCase implements AutoCloseable {
        private final Inputs inputs;
        private final int size;
        private final double[] expected;
        private final double[][] outputs;
        private final MemorySegment x;
        private final MemorySegment y;
        private final MemorySegment z;
        private final MemorySegment result;
        private final MethodHandle direct;
        private final NativeArithmeticBridge.ExecutionContext vmContext;
        private final Action[] actions;

        private BatchCase(Arena arena, NativeArithmeticBridge vm,
                          MethodHandle direct, int size) {
            this.size = size;
            this.direct = direct;
            inputs = ThreeWayBenchmark.createArithmeticInputs(size);
            expected = new double[size];
            outputs = new double[NAMES.length][size];
            x = arena.allocate(JAVA_DOUBLE, size);
            y = arena.allocate(JAVA_DOUBLE, size);
            z = arena.allocate(JAVA_DOUBLE, size);
            result = arena.allocate(JAVA_DOUBLE, size + 1L);
            result.setAtIndex(JAVA_DOUBLE, size, 1234567.25);
            vmContext = vm.createContext(
                    ThreeWayBenchmark.arithmeticBenchmarkProgram(), size);
            actions = new Action[]{
                    () -> ThreeWayBenchmark.optimizedJavaBatch(inputs, outputs[0]),
                    () -> vmContext.evaluate(
                            inputs.x(), inputs.y(), inputs.z(), outputs[1], size),
                    this::withCopies,
                    this::prepared
            };
            copyInputs();
        }

        private void copyInputs() {
            MemorySegment.copy(inputs.x(), 0, x, JAVA_DOUBLE, 0, size);
            MemorySegment.copy(inputs.y(), 0, y, JAVA_DOUBLE, 0, size);
            MemorySegment.copy(inputs.z(), 0, z, JAVA_DOUBLE, 0, size);
        }

        private void prepared() throws Throwable {
            int status = (int) direct.invokeExact(x, y, z, result, (long) size);
            if (status != 0) {
                throw new IllegalStateException("Direct C returned status " + status);
            }
        }

        private void withCopies() throws Throwable {
            copyInputs();
            prepared();
            MemorySegment.copy(result, JAVA_DOUBLE, 0, outputs[2], 0, size);
        }

        private double[] output(int contender) {
            if (contender == 3) {
                // Outside timing: materialize prepared-buffer results for checking.
                // Вне таймера: переносим результат prepared-buffer для проверки.
                MemorySegment.copy(result, JAVA_DOUBLE, 0, outputs[3], 0, size);
            }
            return outputs[contender];
        }

        private void gate(String phase) throws Throwable {
            ThreeWayBenchmark.optimizedJavaBatch(inputs, expected);
            for (int contender = 0; contender < actions.length; contender++) {
                actions[contender].run();
                double[] actual = output(contender);
                for (int index = 0; index < size; index++) {
                    long wanted = Double.doubleToRawLongBits(expected[index]);
                    long found = Double.doubleToRawLongBits(actual[index]);
                    if (wanted != found) {
                        throw new IllegalStateException(String.format(Locale.ROOT,
                                "W2 direct experiment raw-bit mismatch; phase=%s; "
                                        + "size=%d; contender=%s; index=%d; "
                                        + "Java=0x%016x; actual=0x%016x; "
                                        + "x=%s; y=%s; z=%s",
                                phase, size, NAMES[contender], index, wanted, found,
                                Double.toHexString(inputs.x()[index]),
                                Double.toHexString(inputs.y()[index]),
                                Double.toHexString(inputs.z()[index])));
                    }
                }
            }
            if (result.getAtIndex(JAVA_DOUBLE, size) != 1234567.25) {
                throw new IllegalStateException("Output overrun at size " + size);
            }
            System.out.println("Raw-bit PASS: " + phase + " size=" + size);
        }

        private void warmup() throws Throwable {
            for (int contender = 0; contender < actions.length; contender++) {
                long start = System.nanoTime();
                int calls = 0;
                do {
                    actions[contender].run();
                    consume(checksum(output(contender)));
                    calls++;
                } while (calls < 20 || System.nanoTime() - start < WARMUP_NS);
                System.out.println("Warmup: size=" + size + " contender="
                        + NAMES[contender] + " calls=" + calls);
            }
        }

        private void measure(StringBuilder report) throws Throwable {
            int repeats = Math.max(1, 65536 / size);
            double[][] samples = new double[actions.length][SAMPLES];
            long[] checksums = new long[actions.length];
            for (int sample = 0; sample < SAMPLES; sample++) {
                for (int step = 0; step < actions.length; step++) {
                    int contender = (sample + step) % actions.length;
                    long elapsed = 0;
                    for (int repeat = 0; repeat < repeats; repeat++) {
                        long start = System.nanoTime();
                        actions[contender].run();
                        elapsed += System.nanoTime() - start;
                        // Consume every batch outside timing, including prepared C.
                        // Учитываем каждый пакет вне таймера, включая prepared C.
                        long checksum = checksum(output(contender));
                        checksums[contender] =
                                Long.rotateLeft(checksums[contender], 7) ^ checksum;
                        consume(checksum);
                    }
                    samples[contender][sample] =
                            (double) elapsed / ((long) repeats * size);
                }
            }
            for (int contender = 0; contender < actions.length; contender++) {
                double[] sorted = samples[contender].clone();
                Arrays.sort(sorted);
                String row = String.format(Locale.ROOT,
                        "| %d | %s | %.3f | %.3f | %.3f | %d | `0x%016x` |%n",
                        size, NAMES[contender], sorted[SAMPLES / 2],
                        sorted[0], sorted[(int) Math.ceil(SAMPLES * 0.9) - 1],
                        repeats, checksums[contender]);
                report.append(row);
                // Full samples allow independent analysis across JVM forks.
                // Полные выборки позволяют анализировать независимые JVM-запуски.
                report.append("<!-- samples size=").append(size)
                        .append(" contender=").append(contender).append(": ")
                        .append(Arrays.toString(samples[contender])).append(" -->\n");
            }
        }

        @Override
        public void close() {
            vmContext.close();
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Throwable;
    }

    private static long checksum(double[] values) {
        long result = 0x9e3779b97f4a7c15L;
        for (double value : values) {
            result = Long.rotateLeft(result, 5) ^ Double.doubleToRawLongBits(value);
        }
        return result;
    }

    private static void consume(long value) {
        blackhole = Long.rotateLeft(blackhole, 7) ^ value;
    }

    private static StringBuilder metadata(Path direct, Path vm) throws Exception {
        if (!Files.isRegularFile(direct) || !Files.isRegularFile(vm)) {
            throw new IOException("Both explicit-path native libraries must exist");
        }
        StringBuilder report = new StringBuilder("# Direct C versus Java JIT\n\n");
        report.append("- Timestamp: ").append(OffsetDateTime.now()).append('\n');
        report.append("- CPU: ").append(System.getProperty("benchmark.cpu",
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown")))
                .append('\n');
        report.append("- OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        report.append("- JDK: ").append(System.getProperty("java.vendor")).append(' ')
                .append(System.getProperty("java.runtime.version")).append('\n');
        report.append("- JVM flags: ")
                .append(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .append('\n');
        report.append("- Git HEAD: ").append(git("rev-parse", "HEAD")).append('\n');
        report.append("- Working tree (measurements include local changes):\n\n```text\n")
                .append(git("status", "--short")).append("\n```\n");
        report.append("- Direct DLL: `").append(direct).append("`; SHA-256: `")
                .append(sha256(direct)).append("`\n");
        report.append("- VM DLL: `").append(vm).append("`; SHA-256: `")
                .append(sha256(vm)).append("`\n");

        Path nativeBuild = direct.getParent();
        report.append("\n## Recorded native build configuration\n\n```text\n");
        for (String line : Files.readAllLines(nativeBuild.resolve("CMakeCache.txt"))) {
            if (line.startsWith("CMAKE_C_COMPILER:")
                    || line.startsWith("CMAKE_BUILD_TYPE:")
                    || line.startsWith("CMAKE_C_FLAGS:")
                    || line.startsWith("CMAKE_C_FLAGS_RELEASE:")
                    || line.startsWith("CMAKE_GENERATOR:")) {
                report.append(line).append('\n');
            }
        }
        List<String> compilerFlags = Files.readAllLines(nativeBuild.resolve("build.ninja"))
                .stream().filter(line -> line.trim().startsWith("FLAGS ="))
                .distinct().toList();
        for (String flags : compilerFlags) {
            report.append(flags).append('\n');
        }
        report.append("```\n\n## Method\n\n")
                .append("- Identical original W2 formula and input arrays; 36 operations.\n")
                .append("- All contexts and arrays are allocated before timing.\n")
                .append("- Warmup: at least 1 second and 20 calls per contender/size; ")
                .append("includes result consumption, so this is not 1 second of pure kernel time.\n")
                .append("- 31 measured samples; each sample averages enough separately ")
                .append("timed batch calls to cover 65536 points. Every result is ")
                .append("checksummed outside timing. Timer overhead is included.\n")
                .append("- Contender order rotates per sample. Sizes run in ascending order.\n")
                .append("- Direct C + copies: three heap-to-native input copies, one ")
                .append("ordinary FFM downcall, status check, one native-to-heap output copy.\n")
                .append("- Prepared C: one downcall and status check, no copies inside ")
                .append("timing; output readback/checksum remains outside timing. ")
                .append("This is NOT an end-to-end heap-array comparison.\n")
                .append("- Reused buffers and checksum traversal make this a warm-cache ")
                .append("experiment. No claim of cache-neutral pure arithmetic latency.\n")
                .append("- Java and direct C are specialized; VM interprets the same ")
                .append("program. Production VM, ABI and loader are unchanged.\n")
                .append("- No epsilon admission, fast-math or forced FMA. ")
                .append("No claim about untested CPUs, real density trees or tile VM.\n");
        return report;
    }

    private static String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IOException("Git metadata command failed: " + output);
        }
        return output;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
