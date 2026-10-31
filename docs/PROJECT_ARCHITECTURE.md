# Native Noise Accelerator — Architecture

This document explains how a server-side Minecraft mod accelerates world generation using native code (code compiled directly for the processor, rather than for the Java virtual machine).

This is a design document. The mod is still under development, so some parts of the design may change during implementation. Terms that may be unfamiliar to players are explained in parentheses when they first appear.

A companion document, `DETAILED_ARCHITECTURE_PLAN.md`, expands this architecture into a detailed implementation plan with full specifications and checklists.

The document contains two parts: the English version first, followed by the same text in Russian.

---

# Part I — English

## 1. Purpose and scope

The mod accelerates the noise stage of vanilla world generation. At this stage, the game uses mathematical formulas called density functions (functions that determine what occupies each point in the world, such as stone, air or water).

To do this, the mod evaluates density-function trees (structures that combine multiple density functions into a complete terrain-generation formula) using native C code. It must meet one strict requirement: **bit-exact parity** (results that are identical down to the last bit) with the Java code that actually generates the world in the currently installed modpack.

If the mod cannot prove that a part produces exactly the same result, it uses Java for that part. It does this at the smallest granularity available.

The first versions cover noise filling during vanilla chunk generation in every dimension. They do not cover:

- Surface rules (rules that determine the appearance of the terrain's top layer, such as grass, sand and snow).
- The storage side of chunk generation (the process of packing generated blocks into chunks).
- Features and structures (additional world elements such as trees, ores and villages).

## 2. Design principles

1. **Parity comes before speed.** If a result is fast but differs from the Java result, it is a defect. This is not an acceptable trade-off.

2. **The reference is the actual Java code.** This means the code of the game that is really installed and running. Native results are compared against this code, not against an assumed vanilla reference. This also provides compatibility with implementations from other mods. No separate special case is needed for each mod.

3. **Compile the live tree.** The compiler takes the loaded world's density-function tree as its input. This tree contains the complete formula that defines the dimension's terrain shape, including all changes made by datapacks and mods. As a result, world generation modified by datapacks is accelerated automatically.

4. **Batch evaluation only.** Every native call processes arrays of points, never a single point. Switching between Java and native code has a fixed overhead. Paying that cost for every point would eliminate the performance gain.

5. **Crash risk minimised by construction.** The native side uses fixed code. It does not allocate memory, perform input or output operations, or start its own threads. It works with fixed-size buffers (preallocated memory areas with predetermined sizes), which Java validates before making a call. If anything fails, the affected branch falls back to Java. This discipline minimises the risk but cannot eliminate it entirely: a fault inside machine code (for example, an invalid memory access) can terminate the process before Java can react, so the engine is kept minimal and is tested in isolation.

6. **Open for contributions.** Supporting a new node or sampler type (a component that generates or processes noise values) requires one small handler module (a piece of code that teaches the compiler how to process a particular formula element) and successful verification. The core engine does not need compatibility changes.

7. **The machine is its own specification.** The same C source code can behave differently when compiled for different processor architectures. For example, processors may combine arithmetic operations differently, system math libraries may produce different results in the last bits, and some code defects may appear on only one architecture.

   For this reason, the project does not maintain tables listing processor architectures. Instead, it tests and measures different kernel variants (alternative implementations of the same computation) on the actual machine. It selects only variants that produce bit-identical results and chooses the fastest among them (Section 3.6). During code review, platform-specific conditional logic is considered a defect.

## 3. Components

### 3.1 Native engine (C, precompiled for each platform and shipped inside the jar)

- A universal interpreter (a program that executes a prepared list of simple commands) for compiled density programs (Section 4). It also includes a catalogue of precompiled leaf kernels (the smallest fixed computations that perform individual operations). These include:
    - The Perlin noise sampler (the classic noise-generation algorithm used as a foundation for Minecraft terrain), including its octaves (layers of the same noise generated at different scales).
    - BlendedNoise (the vanilla algorithm responsible for large-scale terrain noise).
    - Spline evaluation (calculating a value on a smooth curve defined by control points).
    - Other fixed vanilla primitives (basic building blocks of vanilla terrain-generation formulas).

- Players do not need to install a C compiler. The mod's jar contains ready-to-use binaries (compiled native libraries): a Windows x86-64 DLL, a Linux x86-64 SO and an Android ARM64 SO built with the Android NDK. These libraries are extracted and loaded at startup in the same way that the game loads its own native libraries, such as LWJGL.

- Batch-processing loops are vectorised wherever the hardware allows it. This means using processor instructions such as NEON on ARM64 and SSE/AVX on x86-64, which can process several numbers in a single operation. Table-driven operations, such as lookups in the permutation table (a shuffled table of numbers used by the noise sampler), remain scalar (they process one value at a time).

- The engine does not retain state between calls. It is also re-entrant (safe to call simultaneously from multiple threads). Generation worker threads can therefore use it concurrently. They only read the programs and tables.

  The engine communicates back with Java only through the batched callback mechanism described in Section 4.

- The Java-to-C bridge uses FFM only (the Foreign Function and Memory API, a standard part of Java 22 and later). JNI (the classic Java Native Interface) is not used: the project targets current Minecraft versions, which themselves require a modern Java, so Java versions below 22 are not supported. One bridge also means one behaviour to test on every platform, including phone launchers.

  If FFM is unavailable in the Java runtime that runs the game, the accelerator stays disabled and generation proceeds in pure Java.

### 3.2 Java-side compiler

- When a world loads, the compiler walks through every density tree in the noise router of every dimension. The noise router is the collection of formulas a dimension uses: the final terrain shape and additional formulas for biomes and caves.

  The compiler then translates each tree node by node into a density program, using the handler registry (Section 3.3).

- Before generating the program, the compiler performs several optimisation passes:
    - Constant folding (calculating parts of a formula that contain only constant values in advance).
    - Common subexpression elimination (calculating a repeated part of a formula only once and reusing its result).
    - Dead-branch removal (removing parts of a formula whose results are never used).
    - Hoisting of column-constant subtrees (moving calculations that depend only on the X/Z column out of repeated operations, so they are performed once per column).

- The optimisation passes are constrained by the semantic properties that each handler declares for its node type: whether a result can safely be reused, which coordinates it depends on, and the scope of its caches (the regions in which cached values stay valid). A pass may only transform a subtree when the declared properties allow it. Nodes whose properties are not declared are compiled without optimisation.

- **Program verifier:** Before a program runs for the first time, Java checks every command and its operands. It verifies that table indices are within valid ranges, stack depth is limited and callback indices are valid.

  This serves the same purpose as bytecode verification (checking compiled instructions before execution) performed by the Java virtual machine on classes. A program that fails verification never reaches the native engine.

- The verifier also enforces resource limits: maximum program size, table sizes, tree depth and callback counts. A pathological tree built by a datapack or another mod therefore cannot produce an unbounded program or exhaust memory.

- Programs are cached by tree signature (a hash, or short fingerprint, of the tree structure and handler versions).

  Identical trees in different worlds can reuse the same compiled program. However, verification still runs separately for every world because sampler tables depend on the world seed.

### 3.3 Handler registry (the extension point)

- There is one handler for each density-node type and each noise-sampler type. A handler knows how to extract a node's constants and children, generate program instructions for it and provide test vectors (sets of test inputs with known-correct outputs).

- If the compiler encounters an unknown type, it compiles the corresponding subtree as an opaque `CALL_JAVA` node. This command tells the engine to ask Java to calculate that part of the formula (see Section 4).

  Most of the tree can still run natively. Only the unsupported part is evaluated in Java.

- Handlers are registered through the integration interface described in Section 3.9, which each loader adapter exposes at startup. This allows another mod to provide support for its own node types through a small companion module.

  This is the intended pull-request surface (the part of the project where external developers can contribute their own implementations).

- Handlers are versioned by Minecraft version. Node types change between game versions, so the registry keeps these differences inside the individual handler modules.

### 3.4 Verification subsystem (the gate)

The following checks run for each tree whenever a world is entered. They run in this order:

1. **Unit vectors:** Each handler involved in the tree is tested using several types of points:
    - Random points.
    - Adversarial points (deliberately difficult inputs, such as coordinates near the world border, the highest and lowest world heights, and points exactly at cell corners).
    - Points generated using the actual world's seed and sampler tables.

   The results are compared bit by bit against the live Java implementation.

2. **Golden chunks:** Several chunks near the spawn point are first generated using pure Java. Their contents are hashed (converted into a compact fingerprint by processing their blocks).

   The same chunks are then generated through the native engine. Their hashes are compared. The native path is enabled for that tree in that world only if the hashes match.

3. **Probation:** The first batches after the native path is enabled are checked synchronously: every result is compared with Java before the generator may use it, so an unverified result never reaches the world. Later batches are shadow-checked in the background. If any difference is detected, the tree is disabled for the rest of the session.

4. **Failure localisation:** If the golden test fails, the system tests smaller subtrees by repeatedly dividing the tree into smaller parts. This helps identify the exact part that produces incorrect results.

   The log records the responsible node, its type, the point where the mismatch occurred and both calculated values.

   This procedure is part of the design. It is not an emergency error-handling mechanism.

5. **Disable hierarchy:** The system can disable individual components at several levels:

   Primitive → subtree → tree → dimension → entire accelerator.

   Each disable action applies to the current session and the corresponding world signature.

### 3.5 Coverage reporting

- When entering a world, the mod creates a report in the log and a separate report file next to the logs.

  For every tree and every rejected subtree, the report shows which parts run natively and which do not. It also includes reason codes, such as:
    - Unsupported type.
    - Verification mismatch at a specific point.
    - Compilation error.
    - Foreign world generator detected.
    - Another compiler is already present.

  The report also contains the tree signature, platform, handler versions and the kernel-selection table from Section 3.6. It provides developers with all the relevant information in one file. The report does not contain the world seed or personal data.

- The mod also sends a short message to the in-game chat in single-player. On a server, it sends the message to the server console.

  The message shows what percentage of the tree's calculation time is handled natively. If the percentage is below 100%, it asks the user to send the report file to the developers.

  This percentage is weighted by the actual calculation time measured during verification, not by the number of nodes. Counting nodes would be misleading: one unsupported node near the top of a tree can have a much greater impact than a dozen unsupported leaf nodes.

### 3.6 Empirical kernel selection for each machine

The project does not maintain a fixed list of supported processor platforms. ARM64 and x86-64 cover many generations, revisions and system libraries. A fixed architecture table would become outdated very quickly.

Instead, each machine is tested against the requirements at runtime. Every kernel can have several implementation variants. The engine **tests them on the actual machine, keeps only those that produce bit-identical results, benchmarks the remaining variants and selects the fastest one**.

This approach has a long-standing precedent: the VOLK library from the GNU Radio project selects its signal-processing kernels in exactly this way.

Adding an optimised variant for a particular platform does not require changing existing selection logic. The new variant is simply added, and the selection system uses it wherever it is correct and faster. This makes such contributions easy to implement and review.

- **Variant ladder.** Every kernel where exact parity is critical has a portable reference implementation (the simplest, slowest and most universal version). Optimised variants and workaround variants can also be added.

  All variants implement the same mathematics differently. If a variant cannot produce bit-identical results on a particular machine, the system simply does not select it there.

- **Selection run.** Selection runs once per machine and engine build, not every time the game launches.

  For each kernel, every eligible variant is tested against the verification vectors. Variants that produce different results are discarded.

  The remaining variants are benchmarked using realistic workloads and production-sized batches. The fastest one is selected.

  The resulting selection table is cached on disk. Its key includes the processor model, engine build and available variants.

  The table is also included in the coverage report (Section 3.5). This means the report shows exactly which code ran on the user's machine and how fast it performed.

- **Safety of testing suspect code.** Before executing a variant, the system checks the processor's capabilities (by reading the processor's capability registers). This confirms that the processor supports the required instruction set.

  An unsupported instruction must be impossible by construction, not merely unlikely to occur during testing.

  Where the platform allows it, the selection run also takes place in a helper child process (a separate small process) with checkpointed progress. This means that even a memory bug in a variant affects only one profiling attempt, rather than crashing the game.

  On platforms where this is not possible, such as restricted phone-launcher environments, correctness relies on continuous-integration discipline (automated testing of builds and changes), sanitiser-enabled test builds (builds that use special tools to detect memory errors) and the test corpus running on both architectures.

  Conservative defaults are used and are only replaced after a selection run finishes successfully.

- **Timing honesty.** Kernel benchmarks can produce noisy results. Phone processors can also slow down when they become hot during sustained workloads.

  For this reason, benchmarks use realistic batch sizes and median measurements. A new variant must be approximately 5% faster before it can replace the default correct variant.

  The system does not repeat this selection while a game session is running.

  Per-world correctness is checked separately at a later stage using golden chunks (Section 3.4). These checks apply to the already selected configuration. Therefore, choosing between variants that produce bit-identical results cannot change the generated world.

- **What remains of static switches.** Only platform information checked during startup remains static:
    - Library loading.
    - Symbol availability.
    - Baseline instruction-set checks.
    - Configuration kill-switches (emergency switches that force a kernel to use its reference implementation or disable it completely).

  These kill-switches allow a newly discovered platform bug to be neutralised for users before a fix is released.

  Mathematical behaviour is never selected using a fixed platform table. It is tested and measured on the actual machine.

### 3.7 Game integration

- Server-side only. Clients do not need to install anything. On a dedicated server, only the server needs the mod, and all players benefit from it.

  In single-player, the integrated server (the server running inside the player's game client) handles world generation, so one installation is sufficient.

  The main target platforms are therefore Linux x86-64 (commonly used by dedicated servers), Windows and Android ARM64.

- The mod hooks into the noise-filling stage of chunk generation. It leaves the surrounding vanilla systems, including structures, features and lighting, unchanged.

- **Conflict rules:**
    - A complete foreign world generator (a mod that replaces the entire world-generation system), such as Terra, ReTerraForged or Moderner Beta, means there is no vanilla density tree to accelerate. The accelerator remains disabled for that world, and the report explains why.
    - A second density-tree compiler, such as the one in C2ME, must not compete with this mod. The system detects its presence, and a configuration option determines which compiler controls tree evaluation.
    - Custom node types added by other mods, such as Lithostitched or the mod version of Tectonic, remain opaque nodes until suitable handlers are available.
    - Mods that modify biome-placement inputs, such as Biolith or TerraBlender, change the inputs to the evaluation rather than the evaluation itself. They are therefore covered by the live-oracle principle.

### 3.8 Native code in other languages

The engine's contract is not the C language itself, but a binary interface (an application
binary interface, or ABI: the exact set of functions and data layouts both sides agree on),
defined once in a C header file (a file that declares function signatures without implementing
them). C is only the first implementation of that interface. The interface uses only primitive
types (pointers to arrays, lengths and numbers) and the C calling convention (the universal
agreement on how functions receive their arguments), so any language that can produce machine
code behind such functions can implement part of the library - Rust, C++, Zig, or assembly
language itself.

- Mixing languages inside one library is a standard linking step: each language is compiled
  into object files (chunks of machine code with named functions), and the linker (the tool
  that assembles object files into a finished library) joins them into a single DLL or SO. The
  main part of the engine can be written in C while one kernel variant is implemented in Rust
  and another in hand-written assembly; for the game and for Java it remains one library with
  one interface.
- Other languages enter at the finest granularity: as kernel variants (Section 3.6). The
  selection machinery neither knows nor cares which language produced a variant - it tests the
  machine code for bit-exactness and speed, so a variant written in any language is adopted
  only where it is provably equal and faster.
- Rules that keep the boundary safe: no exceptions, panics (a fatal error in Rust terminology)
  or memory allocation may cross the interface; languages that carry heavy runtimes of their
  own (their own thread schedulers or garbage collectors) do not belong inside kernels; all
  exported names carry the project prefix; and the library reports its ABI version when it is
  loaded, which Java checks.
- The source tree mirrors this design: `libs_src/C` holds the C implementation today, and
  `libs_src/<language>` folders can be added next to it, each with its own build script producing
  object files for the shared link step.

### 3.9 Loader support

The accelerator is built as a loader-independent core plus a thin adapter for each supported
mod loader (the program that loads mods into the game).

- The core contains the compiler, the handler registry, verification, reporting, the FFM
  bridge and the native libraries. It depends only on the game's own classes and the standard
  Java library. It never imports the API of any loader - not even for handler registration,
  which goes through an internal integration interface that the adapters implement.
- An adapter has three duties: start the core when the game starts, connect the core to the
  noise-filling stage of chunk generation, and provide the configuration. The hook itself
  targets vanilla game classes, which are the same under every loader, so the integration
  code is shared.
- Supported loaders: Fabric, Quilt and NeoForge. Forge is not supported: it differs from the
  other loaders in its fundamental design, not only in details, and would require a separate
  integration rather than an adapter of the same kind.
- The native libraries do not depend on the loader at all (Section 3.8): the same binaries
  ship in every loader's build. A build for one loader packages the core, the native
  libraries and that loader's adapter.

## 4. The density program (approximate)

- The program uses compact stack-machine bytecode (a short list of simple instructions executed by the engine).

  It supports:
    - Arithmetic and clamp operations (limiting a value to a specified range).
    - Spline evaluation using a constant table.
    - Calls to leaf kernels by index.
    - Cache operations that mirror vanilla's own cache wrappers (mechanisms that reuse values already calculated for a column or cell).
    - `CALL_JAVA` instructions for opaque subtrees.

- Constants are stored in separate tables. These include numbers, spline tables and sampler descriptions that reference the world's permutation tables (the shuffled number tables used by noise samplers).

  This keeps programs compact and their signatures meaningful.

- **Cell interpolation:** Vanilla evaluates interpolated subtrees (parts of a formula whose results are smoothed between points) at cell corners. These cells measure 4×8×4 blocks.

  The game then blends the corner values inside each cell.

  The recommended approach is for the engine to handle this standard cell loop itself: calculate values at the corners and then blend them. The batch layout is regular, and this is where much of the processing overhead occurs.

  Unusual wrappers fall back to evaluating their subtrees point by point inside the same program.

- `CALL_JAVA` works in batches. The engine processes the points until it has collected all callback requests for the entire points array.

  It then returns these requests to Java in one boundary crossing. Java evaluates the requested subtrees for all points, and the engine resumes execution.

  There is one Java/native boundary crossing per program segment, never one per point.

## 5. Where fixes live

- **In the C sources:** Fixes to the mathematics of a leaf kernel and to the exact meaning of the engine's commands are made here.

  After a C-side fix, the engine must be rebuilt for all platforms and included in the next mod release.

- **In the Java classes:** The compiler, handlers, optimisation passes and test corpora (collections of test cases) are maintained here.

- The two verification levels help identify which part contains the defect:
    - Unit vectors isolate individual kernels. If a unit test fails, the problem is in the C implementation.
    - If individual kernels pass but golden chunks fail, the problem is in the compiler, handlers or engine semantics.

  Subtree localisation then helps narrow down the exact cause.

  Until a fix is released, affected branches remain disabled by the runtime verification gate. Configuration kill-switches (Section 3.6) can also immediately disable a problematic kernel.

## 6. Contribution contract (for pull requests)

- To support a new node or sampler type, add a handler module, register it and provide test vectors.

- The public verification harness (the project's testing framework) runs the handler against the live Java implementation and the standard test corpus.

  The standard corpus includes the density trees of all vanilla dimensions and any additional provided test worlds.

- A contribution can be merged when both the standard test corpus and the handler's own test vectors pass.

  Parity is verified automatically by the test harness, not by visual inspection or manual code review. This makes contributions easier and cheaper to accept.

  The same rules apply to new kernel variants. Each variant must be submitted with its own test vectors. The selection system (Section 3.6) then determines whether it can be used on each machine.

## 7. Rollout stages

- **Stage 0 (initial checks):**
    - Profile the noise stage to determine how much time is spent in leaf samplers and how much in tree-processing overhead. No published measurements currently exist for this split.
    - Create a simple hello-world mod to prove that a native library bundled inside a jar can load under the phone launcher.
    - Check FFM availability for each target Java runtime.

- **Stage 1:**
    - Implement the engine and Java/C bridges.
    - Implement the Perlin and BlendedNoise kernels.
    - Add handlers for basic arithmetic nodes.
    - Integrate with vanilla noise filling.
    - Include complete verification and reporting from the first release.

- **Stage 2:**
    - Support all vanilla node types in the current Minecraft version.
    - Move the cell-interpolation loop into the engine.
    - Implement the optimisation passes.

- **Stage 3:**
    - Add handlers for foreign node types, such as Lithostitched and similar mods.
    - Implement coexistence rules for other compilers.

- **Stage 4 (optional):**
    - Develop an equivalent compiler for surface rules.
    - Optimise the storage side of world generation.

## 8. Limitations and open questions (honest assessment)

- The maximum possible improvement is limited by the proportion of world-generation time spent on the noise stage.

  According to the project's own measurements, in a fresh-terrain generation benchmark on a mid-range PC, noise filling accounted for approximately half of the processor time required to generate new chunks region by region.

  However, the division of that time between leaf samplers and tree-processing overhead has not yet been measured.

  Stage 0 exists specifically because this measurement determines the project's expected performance gain.

- Bit-exact results cannot be mathematically proven for every possible input.

  Instead, the design uses the strongest practical verification methods: unit vectors, golden chunks and probation.

  Any remaining risk is logged and the affected code is disabled when a problem is detected. It is never silently ignored. However, the risk is not zero, and this document states that openly.

- An interpreter has dispatch overhead (a small cost every time it selects and executes the next command). Fully specialised code would not have this overhead.

  The project reduces this cost by using a catalogue of fused leaf kernels (kernels that combine several operations into one).

  Generating machine code at runtime is prohibited by operating-system policies on important platforms, particularly Android. This is why the design uses a fixed native engine with separate programs compiled for each world.

- Trees containing many opaque nodes (unsupported nodes that must be evaluated by Java) gradually approach Java's performance. This is an intentional consequence of the fallback design.

- Each Minecraft version can change node types and the internal implementation of noise. Maintaining handlers for every game version is therefore a permanent project expense.

- **Reference points from the ecosystem.**

  The following figures were reported by the respective project authors and have not been independently verified:
    - ZFastNoise reports an overall world-generation improvement of approximately 10–18% from storage-side optimisation alone.
    - C2ME's density-function compiler reports improvements of up to 30% for datapack-based world generation and up to 90% with a Tectonic configuration.

  These are the benchmarks against which this project will be compared.

## 9. Repository layout (sketch)

- `api/` — the handler SPI (a small public interface that external developers use to implement handlers) and the public Java verification harness.
- `runtime/` — the compiler, program verifier, verification subsystem, reporting and Java/C bridges.
- `native/` — the C engine, leaf kernels and platform-specific build scripts.
- `corpus/` — test worlds, golden-chunk fixtures (saved reference data for comparison) and handler test vectors.
- `docs/` — this architecture document and the contributor guide.

## 10. Origin

The idea for this project appeared during the development of the Far Plane Two Continuation mod.

Its performance logs showed how much processing power vanilla noise generation actually requires.

---

# Часть II — Русский перевод

## 1. Назначение и область применения

Мод ускоряет стадию шума в ванильной генерации мира. На этом этапе игра использует математические формулы, называемые функциями плотности (функциями, определяющими, что находится в каждой точке мира: камень, воздух или вода).

Для этого мод вычисляет деревья функций плотности (структуры, объединяющие несколько функций плотности в единую формулу генерации ландшафта) с помощью нативного кода на языке C. Такой код компилируется непосредственно для процессора, а не для виртуальной машины Java.

При этом действует строгое требование: **побитовый паритет** (полное совпадение результатов вплоть до последнего бита) с Java-кодом, который действительно отвечает за генерацию мира в текущей сборке модов.

Если мод не может доказать, что определённая часть вычислений даёт точно такой же результат, эта часть выполняется на Java. Переключение происходит на самом мелком доступном уровне.

Первые версии охватывают шумовое заполнение при генерации ванильных чанков во всех измерениях. Пока они не охватывают:

- Правила поверхности (правила, определяющие внешний вид верхнего слоя ландшафта, например траву, песок и снег).
- Сторону хранения генерации чанков (процесс упаковки готовых блоков в чанки).
- Фичи и структуры (дополнительные элементы мира, например деревья, руды и деревни).

## 2. Принципы проектирования

1. **Паритет важнее скорости.** Если быстрый результат отличается от результата Java, это считается ошибкой, а не допустимым компромиссом.

2. **Эталон — реальный Java-код.** Речь идёт о коде фактически установленной и запущенной игры. Нативные результаты сравниваются именно с ним, а не с предполагаемой эталонной реализацией ваниллы.

   Благодаря этому принципу мод совместим и с реализациями других модов. Для каждого стороннего мода не нужно создавать отдельные исключения.

3. **Компилируем реальное дерево.** Компилятор получает дерево функций плотности загруженного мира. Оно содержит полную формулу формы ландшафта измерения с учётом всех изменений, внесённых датапаками и модами.

   Поэтому генерация мира с изменениями из датапаков ускоряется автоматически.

4. **Только пакетное (batch) вычисление.** Каждый нативный вызов обрабатывает массив точек, а не одну точку.

   Переход между Java и нативным кодом всегда имеет фиксированные накладные расходы. Если выполнять такой переход для каждой точки отдельно, весь выигрыш в производительности исчезнет.

5. **Риск крашей сведён к минимуму по построению.** Нативная сторона использует фиксированный код. Она не выделяет память, не выполняет операции ввода и вывода и не запускает собственных потоков.

   Вместо этого она работает с буферами фиксированного размера (заранее выделенными областями памяти с определёнными размерами). Java проверяет размеры этих буферов до вызова.

   При любом сбое соответствующая ветка переключается на Java. Эта дисциплина сводит риск к минимуму, но не устраняет его полностью: ошибка внутри машинного кода (например, обращение к недопустимой памяти) может завершить процесс до того, как Java успеет отреагировать, поэтому движок делается минимальным и тестируется изолированно.

6. **Открытость для вкладов.** Для поддержки нового типа узла или сэмплера (компонента, который создаёт или обрабатывает значения шума) требуется небольшой модуль обработчика (часть кода, которая объясняет компилятору, как работать с определённым элементом формулы) и успешное прохождение верификации.

   Для добавления совместимости не требуется изменять ядро движка.

7. **Машина сама себе спецификация.** Один и тот же исходный код на C может вести себя по-разному при компиляции для разных архитектур процессоров.

   Например, процессоры могут по-разному объединять арифметические операции, системные математические библиотеки могут выдавать отличающиеся результаты в последних битах, а некоторые ошибки кода могут проявляться только на одной архитектуре.

   Поэтому проект не ведёт таблицы поддерживаемых архитектур. Вместо этого разные варианты ядер (альтернативные реализации одного вычисления) тестируются и замеряются непосредственно на конкретной машине.

   Выбираются только варианты, которые дают побитово одинаковые результаты. Среди них выбирается самый быстрый (раздел 3.6).

   Условия, зависящие от конкретной платформы, при проверке кода считаются дефектом.

## 3. Компоненты

### 3.1 Нативный движок (C, предварительно скомпилированный под каждую платформу и поставляемый внутри jar)

- Универсальный интерпретатор (программа, которая выполняет заранее подготовленный список простых команд) для скомпилированных программ плотности (раздел 4).

  Кроме того, движок содержит каталог предварительно скомпилированных листовых ядер (небольших фиксированных вычислений, выполняющих отдельные операции). В него входят:
    - Сэмплер шума Перлина (классический алгоритм генерации шума, который лежит в основе ландшафта Minecraft), включая его октавы (слои одного и того же шума, генерируемые в разных масштабах).
    - BlendedNoise (ванильный алгоритм, отвечающий за крупномасштабный шум ландшафта).
    - Вычисление сплайнов (получение значения на гладкой кривой, заданной контрольными точками).
    - Другие фиксированные примитивы ваниллы (базовые элементы формул генерации ландшафта).

- Игроку не нужно устанавливать компилятор C. В jar-файл мода уже включены готовые бинарные библиотеки (скомпилированные нативные библиотеки): DLL для Windows x86-64, SO для Linux x86-64 и SO для Android ARM64, собранная с помощью Android NDK.

  При запуске эти библиотеки извлекаются и загружаются так же, как игра загружает собственные нативные библиотеки, например LWJGL.

- Пакетные циклы векторизуются там, где это позволяет оборудование. Для этого используются инструкции NEON на ARM64 и SSE/AVX на x86-64 (инструкции процессора, которые позволяют обрабатывать несколько чисел за одну операцию).

  Табличные операции, например выборки из таблицы перестановок (перетасованной таблицы чисел, на которой строится сэмплер шума), остаются скалярными (обрабатывают по одному значению за раз).

- Движок не сохраняет состояние между вызовами. Он также реентерабелен (несколько потоков могут безопасно обращаться к нему одновременно).

  Поэтому рабочие потоки генерации могут вызывать его параллельно. При этом программы и таблицы используются только для чтения.

  Обратно в Java движок обращается только через пакетный механизм обратных вызовов, описанный в разделе 4.

- Для связи Java и C используется только FFM (интерфейс внешних функций и памяти, Foreign Function and Memory API, стандартная часть Java 22 и более новых версий).

  JNI (классический Java Native Interface) не используется: проект ориентирован на актуальные версии Minecraft, которые сами требуют современную Java, поэтому версии Java ниже 22 не поддерживаются. Один мост означает и одно поведение, которое нужно тестировать на каждой платформе, включая телефонные лаунчеры.

  Если FFM недоступен в среде Java, в которой запущена игра, ускоритель остаётся отключённым, а генерация выполняется полностью на Java.

### 3.2 Компилятор на стороне Java

- При загрузке мира компилятор обходит каждое дерево плотности в маршрутизаторе шума каждого измерения.

  Маршрутизатор шума — это набор формул, которые использует измерение. В него входят итоговая формула формы ландшафта и дополнительные формулы для биомов и пещер.

  Затем компилятор переводит каждое дерево узел за узлом в программу плотности, используя реестр обработчиков (раздел 3.3).

- Перед созданием программы выполняются несколько этапов оптимизации:
    - Свёртка констант (предварительное вычисление частей формулы, которые содержат только постоянные значения).
    - Устранение общих подвыражений (вычисление повторяющейся части формулы только один раз с последующим использованием готового результата).
    - Удаление мёртвых ветвей (удаление частей формулы, результаты которых нигде не используются).
    - Поднятие поддеревьев, постоянных по колонке (перенос вычислений, зависящих только от колонки X/Z, за пределы повторяющихся операций, чтобы выполнять их один раз на колонку).

- Этапы оптимизации ограничены семантическими свойствами, которые каждый обработчик объявляет для своего типа узла: можно ли безопасно переиспользовать результат, от каких координат он зависит и какова область действия его кешей (области, в которых кешированные значения остаются действительными). Этап вправе преобразовывать поддерево только тогда, когда объявленные свойства это разрешают. Узлы, чьи свойства не объявлены, компилируются без оптимизации.

- **Верификатор программ:** Перед первым запуском программы Java проверяет каждую команду и её операнды.

  Проверяется, что индексы таблиц находятся в допустимых пределах, глубина стека ограничена, а индексы обратных вызовов корректны.

  Это выполняет ту же задачу, что и верификация байткода (проверка скомпилированных инструкций перед выполнением), которую виртуальная машина Java проводит для классов.

  Программа, не прошедшая проверку, до нативного движка не доходит.

- Верификатор также налагает ограничения на ресурсы: максимальный размер программы, размеры таблиц, глубину дерева и количество обратных вызовов. Поэтому патологическое дерево, созданное датапаком или другим модом, не может породить неограниченную программу или исчерпать память.

- Программы кэшируются по подписи дерева (хэшу, то есть короткому отпечатку структуры дерева и версий обработчиков).

  Одинаковые деревья в разных мирах могут использовать одну и ту же скомпилированную программу.

  Однако верификация всё равно выполняется отдельно для каждого мира, поскольку таблицы сэмплеров зависят от сида мира (числового значения, из которого генерируется случайная последовательность для мира).

### 3.3 Реестр обработчиков (точка расширения)

- Для каждого типа узла плотности и каждого типа сэмплера шума существует отдельный обработчик.

  Обработчик умеет извлекать константы и дочерние элементы узла, генерировать команды программы и предоставлять тестовые векторы (наборы тестовых входных данных с заранее известными правильными результатами).

- Если во время компиляции встречается неизвестный тип, соответствующее поддерево компилируется как непрозрачный узел `CALL_JAVA`.

  Это команда, которая сообщает движку, что данный участок формулы нужно вычислить с помощью Java (см. раздел 4).

  Большая часть дерева при этом всё равно может выполняться нативно. На Java переводится только неподдерживаемая часть.

- Регистрация обработчиков происходит через интерфейс интеграции, описанный в разделе 3.9, который каждый адаптер загрузчика предоставляет при запуске.

  Благодаря этому другой мод может добавить поддержку собственных типов узлов с помощью небольшого дополнительного модуля.

  Именно для таких внешних вкладов и предназначен механизм pull request (предложение разработчика добавить свои изменения в основной проект).

- Обработчики имеют отдельные версии для разных версий Minecraft.

  Поскольку наборы узлов меняются между версиями игры, реестр хранит эти различия внутри соответствующих модулей обработчиков.

### 3.4 Подсистема верификации (механизм допуска)

При каждом входе в мир каждое дерево проходит следующие проверки. Порядок проверок не меняется:

1. **Модульные тестовые векторы.** Каждый обработчик, используемый в дереве, проверяется на нескольких типах точек:
    - Случайных точках.
    - Состязательных точках (намеренно сложных входных данных, например координатах у границы мира, на минимальной и максимальной высоте мира, а также точках точно в углах ячеек).
    - Точках, полученных с использованием реального сида мира и его таблиц сэмплеров.

   Результаты сравниваются побитово с действующей реализацией Java.

2. **Золотые чанки.** Несколько чанков рядом со спауном сначала генерируются с использованием только Java.

   Затем их содержимое хэшируется (преобразуется в компактный отпечаток на основе содержащихся в них блоков).

   После этого те же чанки генерируются через нативный движок, а полученные хэши сравниваются.

   Нативный путь для данного дерева в этом мире включается только в случае полного совпадения хэшей.

3. **Испытательный срок.** Первые пакетные вычисления после включения нативного пути проверяются синхронно: каждый результат сравнивается с Java до того, как генератор сможет его использовать, поэтому непроверенный результат никогда не попадает в мир.

   Последующие пакеты проверяются теневой проверкой: вычисления повторяются в фоне и сравниваются с результатами Java.

   Если обнаруживается расхождение, дерево отключается до конца текущей сессии.

4. **Локализация отказа.** Если проверка золотых чанков не пройдена, система начинает проверять меньшие поддеревья, последовательно разделяя исходное дерево на части.

   Это помогает определить конкретный участок, который выдаёт неправильные результаты.

   В лог записываются виновный узел, его тип, точка, в которой произошло расхождение, и оба полученных значения.

   Эта процедура заранее предусмотрена архитектурой и не является аварийным механизмом.

5. **Иерархия отключений.** Система может отключать компоненты на нескольких уровнях:

   Примитив → поддерево → дерево → измерение → весь ускоритель.

   Каждое отключение действует до конца текущей сессии и привязывается к подписи мира.

### 3.5 Отчётность о покрытии

- При входе в мир мод создаёт блок отчёта в логе и отдельный файл рядом с игровыми логами.

  Для каждого дерева и каждого отклонённого поддерева в отчёте указывается, какие части выполняются нативно, а какие нет.

  Также приводятся коды причин:
    - Тип не поддерживается.
    - Верификация обнаружила несовпадение в конкретной точке.
    - Ошибка компиляции.
    - Обнаружен сторонний генератор мира.
    - Уже присутствует другой компилятор.

  Кроме того, отчёт содержит подпись дерева, платформу, версии обработчиков и таблицу подбора ядер из раздела 3.6. Сид мира и личные данные в отчёт не включаются.

  Таким образом, разработчики получают всю необходимую информацию в одном файле.

- В одиночной игре мод отправляет короткое сообщение в игровой чат. На сервере сообщение выводится в консоль сервера.

  В сообщении указывается, какой процент времени вычисления дерева выполняется нативно.

  Если этот показатель ниже 100%, мод просит пользователя отправить файл отчёта разработчикам.

  Процент рассчитывается по фактическому времени вычислений, измеренному во время верификации, а не по количеству узлов.

  Подсчёт узлов мог бы вводить в заблуждение: один неподдерживаемый узел в верхней части дерева может влиять на производительность гораздо сильнее, чем дюжина неподдерживаемых листовых узлов.

### 3.6 Эмпирический подбор ядер под конкретную машину

Проект не ведёт фиксированный список поддерживаемых процессорных платформ.

ARM64 и x86-64 охватывают множество поколений процессоров, ревизий и системных библиотек. Поэтому таблица архитектур очень быстро устаревала бы.

Вместо этого каждая машина проверяется непосредственно во время работы. Для каждого ядра может существовать несколько вариантов реализации.

Движок **тестирует эти варианты на конкретной машине, оставляет только те, которые дают побитово одинаковые результаты, замеряет их производительность и выбирает самый быстрый**.

У такого подхода есть давний пример: библиотека VOLK проекта GNU Radio подбирает ядра обработки сигналов точно таким же способом.

Добавление оптимизированного варианта для определённой платформы не требует изменения существующей логики подбора. Новый вариант просто добавляется, а система начинает использовать его на всех машинах, где он работает корректно и быстрее остальных.

Это делает подобные вклады простыми в реализации и проверке.

- **Лесенка вариантов.** У каждого ядра, для которого особенно важен побитовый паритет, есть переносимая эталонная реализация (самый простой, медленный и универсальный вариант).

  Дополнительно можно создавать оптимизированные варианты и варианты-обходные пути.

  Все варианты реализуют одну и ту же математику разными способами. Если какой-либо вариант не может обеспечить побитовое совпадение на конкретной машине, система просто не выбирает его на этой машине.

- **Прогон подбора.** Подбор выполняется один раз для каждой машины и сборки движка, а не при каждом запуске игры.

  Для каждого ядра все подходящие варианты проверяются на верификационных векторах. Варианты, выдающие отличающиеся результаты, отбрасываются.

  Оставшиеся варианты проходят бенчмарк (измерение производительности) на реалистичных нагрузках и пакетах данных производственного размера.

  После этого выбирается самый быстрый вариант.

  Итоговая таблица подбора сохраняется на диске в кэше. Её ключ включает модель процессора, сборку движка и набор доступных вариантов.

  Эта таблица также дословно включается в отчёт о покрытии (раздел 3.5). Поэтому отчёт показывает, какой именно код выполнялся на машине пользователя и какую производительность он продемонстрировал.

- **Безопасность тестирования подозрительного кода.** Перед запуском любого варианта система проверяет возможности процессора (читает специальные регистры, в которых процессор сообщает о поддерживаемых возможностях).

  Это позволяет убедиться, что процессор поддерживает необходимый набор инструкций.

  Выполнение неподдерживаемой инструкции должно быть исключено самой архитектурой системы, а не просто считаться маловероятным.

  Если платформа это позволяет, прогон подбора дополнительно выполняется в дочернем процессе-помощнике (отдельном небольшом процессе) с сохранением промежуточного прогресса.

  Благодаря этому даже ошибка работы с памятью в одном из вариантов приведёт к потере только одной попытки профилирования, а не к аварийному завершению игры.

  На платформах, где это невозможно, например в ограниченных окружениях телефонных лаунчеров, корректность обеспечивается дисциплиной непрерывной интеграции (автоматизированной проверкой изменений и сборок), тестовыми сборками с санитайзерами (инструментами, обнаруживающими ошибки работы с памятью) и тестовым набором, который запускается на обеих архитектурах.

  При этом используются консервативные настройки по умолчанию. Они заменяются более быстрыми вариантами только после успешного завершения подбора.

- **Честность замеров.** Результаты замеров производительности ядер могут быть нестабильными.

  Кроме того, процессоры телефонов могут замедляться при длительной нагрузке из-за нагрева.

  Поэтому бенчмарки используют реалистичные размеры пакетов и медианные результаты измерений.

  Чтобы новый вариант заменил корректный вариант по умолчанию, он должен быть быстрее примерно на 5%.

  Во время игровой сессии подбор не запускается повторно.

  Корректность для конкретного мира проверяется отдельно, на следующем этапе, с помощью золотых чанков (раздел 3.4).

  Эти проверки выполняются уже для выбранной конфигурации. Поэтому выбор между вариантами, которые дают побитово одинаковые результаты, не может изменить генерацию мира.

- **Что осталось от статических переключателей.** Статическими остаются только факты о платформе, которые проверяются при запуске:
    - Загрузка библиотек.
    - Доступность символов (наличие необходимых функций и точек входа в библиотеке).
    - Проверка базового набора инструкций процессора.
    - Аварийные выключатели в конфигурации (настройки, позволяющие принудительно использовать эталонный вариант ядра или полностью отключить его).

  Такие выключатели позволяют временно нейтрализовать обнаруженную ошибку на определённой платформе ещё до выпуска исправления.

  Математическое поведение никогда не выбирается по статической таблице платформ. Оно проверяется и измеряется непосредственно на конкретной машине.

### 3.7 Интеграция с игрой

- **Только серверная сторона.** Клиентам ничего устанавливать не нужно.

  На выделенном сервере мод устанавливается только на сервер, но преимущества от него получают все игроки.

  В одиночной игре генерацией мира занимается интегрированный сервер (сервер, работающий непосредственно внутри игрового клиента). Поэтому достаточно одной установки.

  Основные целевые платформы:
    - Linux x86-64 (наиболее распространённая платформа для выделенных серверов).
    - Windows.
    - Android ARM64.

- Мод подключается к этапу шумового заполнения при генерации чанков.

  Остальные механизмы ваниллы, включая структуры, фичи и освещение, остаются без изменений.

- **Правила совместимости:**
    - Если другой мод полностью заменяет генератор мира (то есть всю систему генерации ландшафта), например Terra, ReTerraForged или Moderner Beta, ускорять становится нечего: дерева функций плотности ваниллы больше нет. В таком мире ускоритель остаётся отключённым, а причина указывается в отчёте.
    - Второй компилятор деревьев функций плотности, например компилятор из C2ME, не должен конфликтовать с этим модом. Система обнаруживает его присутствие, а специальная настройка определяет, какой компилятор будет отвечать за вычисление деревьев.
    - Собственные типы узлов, добавленные другими модами, например Lithostitched или модифицированной версией Tectonic, остаются непрозрачными узлами до тех пор, пока для них не появятся соответствующие обработчики.
    - Моды, изменяющие входные данные для размещения биомов, например Biolith или TerraBlender, меняют исходные данные вычислений, а не сами вычисления. Поэтому они совместимы с принципом использования реального Java-кода в качестве эталона.

### 3.8 Нативный код на других языках

Контракт движка - не сам язык C, а бинарный интерфейс (прикладной бинарный интерфейс, или ABI:
точный набор функций и форматов данных, о которых договорились обе стороны), определённый один
раз в заголовочном файле C (файле, который объявляет сигнатуры функций, не реализуя их). C -
лишь первая реализация этого интерфейса. Интерфейс использует только примитивные типы (указатели
на массивы, длины и числа) и соглашение о вызовах C (универсальную договорённость о том, как
функции получают свои аргументы), поэтому любую часть библиотеки может реализовать любой язык,
способный производить машинный код за такими функциями: Rust, C++, Zig или сам ассемблер.

- Смешение языков внутри одной библиотеки - стандартный шаг линковки: каждый язык компилируется
  в объектные файлы (куски машинного кода с именованными функциями), а линкер (инструмент,
  собирающий объектные файлы в готовую библиотеку) соединяет их в один DLL или SO. Основная
  часть движка может быть написана на C, один вариант ядра - на Rust, а другой - на ассемблере
  вручную; для игры и для Java это остаётся одна библиотека с одним интерфейсом.
- Другие языки входят с самой мелкой гранулярностью: как варианты ядер (раздел 3.6). Механизму
  подбора всё равно, какой язык произвёл вариант, - он тестирует машинный код на побитовую
  точность и скорость, поэтому вариант на любом языке принимается только там, где он доказуемо
  равен и быстрее.
- Правила, которые держат границу безопасной: через интерфейс не должны проходить исключения,
  паники (фатальные ошибки в терминологии Rust) и выделение памяти; языки с тяжёлыми
  собственными средами выполнения (своими планировщиками потоков или сборщиками мусора) не
  должны находиться внутри ядер; все экспортируемые имена несут префикс проекта; а библиотека
  при загрузке сообщает свою версию ABI, которую Java проверяет.
- Устройство исходников повторяет этот дизайн: в `libs_src/C` сегодня лежит реализация на C, а
  рядом могут добавляться папки `libs_src/<язык>`, каждая со своим сборочным скриптом, производящим
  объектные файлы для общего шага линковки.

### 3.9 Поддержка загрузчиков

Ускоритель строится как независимое от загрузчика ядро плюс тонкий адаптер под каждый
поддерживаемый загрузчик модов (программу, которая загружает моды в игру).

- Ядро содержит компилятор, реестр обработчиков, верификацию, отчётность, мост FFM и
  нативные библиотеки. Оно зависит только от классов самой игры и стандартной библиотеки
  Java. Оно никогда не импортирует API какого-либо загрузчика - даже для регистрации
  обработчиков, которая проходит через внутренний интерфейс интеграции, реализуемый
  адаптерами.
- У адаптера три обязанности: запустить ядро при старте игры, подключить ядро к стадии
  шумового заполнения генерации чанков и предоставить конфигурацию. Сам хук ставится в
  ванильные классы игры, которые одинаковы под всеми загрузчиками, поэтому код интеграции
  общий.
- Поддерживаемые загрузчики: Fabric, Quilt и NeoForge. Forge не поддерживается: он
  отличается от остальных загрузчиков принципиальным устройством, а не только деталями, и
  потребовал бы отдельной интеграции, а не адаптера того же вида.
- Нативные библиотеки от загрузчика не зависят вообще (раздел 3.8): одни и те же бинарники
  поставляются в сборке под каждый загрузчик. Сборка под конкретный загрузчик упаковывает
  ядро, нативные библиотеки и адаптер этого загрузчика.

## 4. Программа плотности (приблизительная структура)

- Программа использует компактный байткод стековой машины (короткий список простых инструкций, которые движок выполняет последовательно).

  Он поддерживает:
    - Арифметические операции и операции ограничения диапазона (clamp).
    - Вычисление сплайнов с использованием таблицы констант.
    - Вызовы листовых ядер по индексу.
    - Операции кэширования, повторяющие работу ванильных кэш-обёрток (механизмов, которые позволяют повторно использовать значение, уже вычисленное для колонки или ячейки).
    - Инструкции `CALL_JAVA` для непрозрачных поддеревьев.

- Константы хранятся в отдельных таблицах. К ним относятся числа, таблицы сплайнов и описания сэмплеров, ссылающиеся на таблицы перестановок мира (перетасованные таблицы чисел, используемые сэмплерами шума).

  Благодаря этому программы остаются компактными, а их подписи сохраняют смысл.

- **Интерполяция ячеек.** Ванилла вычисляет интерполируемые поддеревья (части формулы, значения которых сглаживаются между точками) в углах ячеек.

  Размер этих ячеек составляет 4×8×4 блока.

  Затем игра смешивает значения, полученные в углах, чтобы вычислить промежуточные значения внутри каждой ячейки.

  Рекомендуется поручить движку весь стандартный цикл интерполяции: сначала вычислять значения в углах, затем смешивать их.

  Структура пакетных данных здесь регулярна, и именно на этом этапе возникает значительная часть накладных расходов.

  Для нестандартных обёрток используется запасной вариант: вычисление поддерева отдельно для каждой точки внутри той же программы.

- `CALL_JAVA` работает пакетно.

  Движок выполняет программу, пока не соберёт запросы обратных вызовов для всего массива точек.

  Затем он возвращает все запросы в Java за один переход между Java и нативным кодом.

  Java вычисляет запрошенные поддеревья для всех точек, после чего движок продолжает выполнение.

  На один сегмент программы приходится один переход между Java и нативным кодом, а не отдельный переход для каждой точки.

## 5. Где исправлять ошибки

- **В исходниках C:** Здесь исправляются математические операции листовых ядер и точная семантика (значение и правила работы) команд движка.

  После исправления C-кода движок пересобирается для всех платформ и включается в следующую версию мода.

- **В Java-классах:** Здесь находятся компилятор деревьев, обработчики, этапы оптимизации и тестовые корпуса (наборы тестовых случаев).

- Два уровня верификации помогают определить, где находится ошибка:
    - Модульные тестовые векторы проверяют отдельные ядра. Если такой тест не проходит, проблема находится в реализации на C.
    - Если отдельные ядра проходят тесты, но золотые чанки отличаются, проблема находится в компиляторе, обработчиках или семантике движка.

  Дополнительная локализация поддеревьев помогает определить точную причину.

  До выпуска исправления затронутые ветки остаются отключёнными механизмом проверки во время выполнения.

  Аварийные выключатели в конфигурации (раздел 3.6) также позволяют немедленно отключить проблемное ядро.

## 6. Правила внесения вкладов (для pull request)

- Для поддержки нового типа узла или сэмплера необходимо добавить модуль обработчика, зарегистрировать его и предоставить тестовые векторы.

- Публичный верификационный стенд (собственная система тестирования проекта) проверяет обработчик на действующей реализации Java и стандартном тестовом корпусе.

  В стандартный корпус входят деревья всех ванильных измерений и предоставленные тестовые миры.

- Вклад можно принять в основной проект, если успешно проходят как стандартный тестовый корпус, так и собственные тестовые векторы обработчика.

  Побитовый паритет проверяется автоматически с помощью тестового стенда, а не на глаз при просмотре кода. Благодаря этому принимать новые вклады проще и быстрее.

  Те же правила применяются к новым вариантам ядер. Каждый вариант предоставляется вместе со своими тестовыми векторами.

  Затем система подбора (раздел 3.6) самостоятельно определяет, можно ли использовать этот вариант на конкретной машине.

## 7. Этапы разработки и внедрения

- **Этап 0 (первоначальные проверки):**
    - Провести профилирование стадии шума (измерение того, сколько времени занимают отдельные вычисления), чтобы определить долю времени сэмплеров и накладных расходов обработки деревьев. Опубликованных данных по этому разделению пока нет.
    - Создать простой hello-world мод (минимальный тестовый мод), который докажет, что нативную библиотеку, включённую в jar, можно загрузить через телефонный лаунчер.
    - Проверить доступность FFM для каждой целевой версии Java.

- **Этап 1:**
    - Реализовать движок и мосты между Java и C.
    - Реализовать ядра Перлина и BlendedNoise.
    - Добавить обработчики базовых арифметических узлов.
    - Интегрировать движок с ванильным шумовым заполнением.
    - С первого дня включить полноценную верификацию и отчётность.

- **Этап 2:**
    - Обеспечить поддержку всех ванильных типов узлов текущей версии Minecraft.
    - Перенести цикл интерполяции ячеек в движок.
    - Реализовать этапы оптимизации.

- **Этап 3:**
    - Добавить обработчики сторонних типов узлов, например Lithostitched и аналогичных модов.
    - Реализовать правила совместной работы с другими компиляторами.

- **Этап 4 (необязательный):**
    - Разработать аналогичный компилятор для правил поверхности.
    - Оптимизировать сторону хранения генерации мира.

## 8. Ограничения и открытые вопросы (честная оценка)

- Максимальный возможный прирост производительности ограничен долей времени генерации, которую занимает стадия шума.

  Согласно собственным измерениям проекта, при тестировании генерации нового ландшафта на ПК среднего класса шумовое заполнение занимало примерно половину процессорного времени, необходимого для последовательной генерации новых чанков.

  Однако разделение этого времени между листовыми сэмплерами и обработкой деревьев пока не измерено.

  Этап 0 существует именно для того, чтобы получить это измерение. От него зависит оценка ожидаемого прироста производительности.

- Математически доказать побитовое совпадение для всех возможных входных данных нельзя.

  Вместо этого архитектура использует наиболее надёжные практические способы проверки: модульные тестовые векторы, золотые чанки и испытательный срок.

  Остаточный риск записывается в лог. Если обнаруживается расхождение, соответствующий участок отключается. Ошибка никогда не игнорируется молча.

  Однако риск не равен нулю, и в документе это прямо признаётся.

- У интерпретатора есть накладные расходы диспетчеризации (небольшая дополнительная затрата времени на выбор и выполнение каждой следующей команды).

  У полностью специализированного кода таких расходов не было бы.

  Для уменьшения этих затрат используется каталог объединённых листовых ядер (ядер, которые объединяют несколько операций в одно вычисление).

  Генерация машинного кода во время работы программы запрещена политиками операционных систем на некоторых важных платформах, особенно Android.

  Поэтому архитектура использует постоянный нативный движок и отдельные программы для каждого мира.

- Если дерево содержит много непрозрачных узлов (неподдерживаемых узлов, вычисления которых приходится передавать Java), его производительность постепенно приближается к обычной производительности Java.

  Это предусмотренное следствие механизма отката.

- Каждая версия Minecraft может менять типы узлов и внутреннее устройство шума.

  Поэтому поддержка обработчиков для каждой версии игры является постоянной статьёй расходов проекта.

- **Ориентиры из экосистемы.**

  Ниже приведены цифры, заявленные авторами соответствующих проектов. Независимой проверки этих показателей не проводилось:
    - ZFastNoise заявляет об общем улучшении генерации мира примерно на 10–18% только за счёт оптимизации хранения.
    - Компилятор функций плотности из C2ME заявляет об улучшении до 30% при генерации мира с датапаками и до 90% при использовании конфигурации Tectonic.

  Именно с этими показателями будет сравниваться рассматриваемый проект.

## 9. Устройство репозитория (эскиз)

- `api/` — SPI обработчиков (небольшой публичный интерфейс, который используют внешние разработчики для создания собственных обработчиков) и публичный верификационный стенд на Java.
- `runtime/` — компилятор, верификатор программ, подсистема верификации, отчётность и мосты между Java и C.
- `native/` — движок на C, листовые ядра и сборочные скрипты для разных платформ.
- `corpus/` — тестовые миры, фикстуры золотых чанков (сохранённые эталонные данные для сравнения) и тестовые векторы обработчиков.
- `docs/` — этот архитектурный документ и руководство для участников проекта.

## 10. Происхождение идеи

Идея проекта появилась во время разработки мода Far Plane Two Continuation.

Логи производительности показали, насколько много ресурсов на самом деле требует ванильная генерация шума.