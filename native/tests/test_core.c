#include "cnoise.h"

#include <fenv.h>
#include <float.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#if defined(_M_X64) || defined(__x86_64__)
    #include <xmmintrin.h>
#endif

#define ARRAY_COUNT(values) (sizeof(values) / sizeof((values)[0]))
#define SENTINEL_BITS UINT64_C(0x405edd2f1a9fbe77)

static unsigned int checks;
static unsigned int failures;

#define CHECK(condition) do { \
    ++checks; \
    if (!(condition)) { \
        ++failures; \
        fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); \
    } \
} while (0)

typedef struct fixture {
    cnoise_instruction code[16];
    double constants[4];
    double x[8];
    double y[8];
    double z[8];
    double results[8];
    double scratch[8];
    cnoise_program_view program;
    cnoise_batch_view batch;
    cnoise_result_view output;
    cnoise_workspace_view workspace;
} fixture;

static uint64_t raw_bits(double value) {
    uint64_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static double from_bits(uint64_t bits) {
    double value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static void initialize(fixture *f) {
    size_t i;
    memset(f, 0, sizeof(*f));

    f->code[0].opcode = CNOISE_OP_PUSH_COORD_X;
    f->code[1].opcode = CNOISE_OP_RETURN;

    f->program.struct_size = (uint32_t)sizeof(f->program);
    f->program.program_version = CNOISE_PROGRAM_VERSION;
    f->program.instructions = f->code;
    f->program.instruction_count = 2;
    f->program.max_stack = 1;
    f->program.constants = f->constants;
    f->program.constant_count = (uint32_t)ARRAY_COUNT(f->constants);

    f->batch.struct_size = (uint32_t)sizeof(f->batch);
    f->batch.x = f->x;
    f->batch.y = f->y;
    f->batch.z = f->z;
    f->batch.point_count = ARRAY_COUNT(f->x);

    f->output.struct_size = (uint32_t)sizeof(f->output);
    f->output.values = f->results;
    f->output.capacity = ARRAY_COUNT(f->results);

    f->workspace.struct_size = (uint32_t)sizeof(f->workspace);
    f->workspace.values = f->scratch;
    f->workspace.capacity = ARRAY_COUNT(f->scratch);

    for (i = 0; i < ARRAY_COUNT(f->x); ++i) {
        f->x[i] = (double)i - 3.0;
        f->y[i] = (double)i + 2.0;
        f->z[i] = 10.0 - (double)i;
        f->results[i] = from_bits(SENTINEL_BITS);
    }
}

static int32_t evaluate(fixture *f) {
    return cnoise_eval_batch(
        &f->program, &f->batch, &f->output, &f->workspace);
}

static void check_unchanged(const fixture *f) {
    size_t i;
    for (i = 0; i < ARRAY_COUNT(f->results); ++i) {
        CHECK(raw_bits(f->results[i]) == SENTINEL_BITS);
    }
}

static void binary_program(fixture *f, uint32_t opcode) {
    f->code[0].opcode = CNOISE_OP_PUSH_COORD_X;
    f->code[1].opcode = CNOISE_OP_PUSH_COORD_Y;
    f->code[2].opcode = opcode;
    f->code[3].opcode = CNOISE_OP_RETURN;
    f->program.instruction_count = 4;
    f->program.max_stack = 2;
}

static void test_layouts(void) {
    const uint32_t sizes[] = {0, 8, 40, 40, 24, 24};
    const uint32_t alignments[] = {0, 4, 8, 8, 8, 8};
    uint32_t i;

    CHECK(cnoise_abi_version() == CNOISE_ABI_VERSION);
    for (i = 0; i < (uint32_t)ARRAY_COUNT(sizes); ++i) {
        CHECK(cnoise_layout_size(i) == sizes[i]);
        CHECK(cnoise_layout_alignment(i) == alignments[i]);
    }
    CHECK(cnoise_layout_size(UINT32_MAX) == 0);
    CHECK(cnoise_layout_alignment(UINT32_MAX) == 0);
}

static void test_coordinates_and_constants(void) {
    fixture f;
    uint32_t depth = UINT32_MAX;
    size_t i;
    const uint32_t operations[] = {
        CNOISE_OP_PUSH_COORD_X,
        CNOISE_OP_PUSH_COORD_Y,
        CNOISE_OP_PUSH_COORD_Z
    };
    size_t op;

    initialize(&f);
    CHECK(cnoise_verify_program(&f.program, &depth) == CNOISE_OK);
    CHECK(depth == 1);

    for (op = 0; op < ARRAY_COUNT(operations); ++op) {
        const double *expected = op == 0 ? f.x : (op == 1 ? f.y : f.z);
        f.code[0].opcode = operations[op];
        CHECK(evaluate(&f) == CNOISE_OK);
        for (i = 0; i < ARRAY_COUNT(f.results); ++i) {
            CHECK(raw_bits(f.results[i]) == raw_bits(expected[i]));
        }
    }

    f.code[0].opcode = CNOISE_OP_PUSH_CONST;
    f.code[0].operand = 3;
    f.constants[3] = from_bits(UINT64_C(0x8000000000000000));
    CHECK(evaluate(&f) == CNOISE_OK);
    for (i = 0; i < ARRAY_COUNT(f.results); ++i) {
        CHECK(raw_bits(f.results[i]) == UINT64_C(0x8000000000000000));
    }

    f.constants[3] = from_bits(UINT64_C(0x7ff8000000001234));
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == UINT64_C(0x7ff8000000001234));

    initialize(&f);
    f.program.constants = NULL;
    f.program.constant_count = 0;
    f.batch.y = f.x;
    f.batch.z = f.x;
    CHECK(evaluate(&f) == CNOISE_OK);
}

static void test_arithmetic(void) {
    const uint32_t operations[] = {
        CNOISE_OP_ADD, CNOISE_OP_SUB, CNOISE_OP_MUL,
        CNOISE_OP_DIV, CNOISE_OP_MIN, CNOISE_OP_MAX
    };
    fixture f;
    size_t op;
    size_t i;

    for (op = 0; op < ARRAY_COUNT(operations); ++op) {
        initialize(&f);
        binary_program(&f, operations[op]);
        CHECK(evaluate(&f) == CNOISE_OK);

        for (i = 0; i < ARRAY_COUNT(f.results); ++i) {
            double expected = 0.0;
            double x = f.x[i];
            double y = f.y[i];
            switch (operations[op]) {
                case CNOISE_OP_ADD: expected = x + y; break;
                case CNOISE_OP_SUB: expected = x - y; break;
                case CNOISE_OP_MUL: expected = x * y; break;
                case CNOISE_OP_DIV: expected = x / y; break;
                case CNOISE_OP_MIN: expected = x < y ? x : y; break;
                case CNOISE_OP_MAX: expected = x > y ? x : y; break;
            }
            CHECK(raw_bits(f.results[i]) == raw_bits(expected));
        }
    }

    /* Cancellation must not be contracted to an FMA. */
    initialize(&f);
    f.batch.point_count = 1;
    f.x[0] = 0x1.0000000000001p0;
    f.y[0] = 0x1.fffffffffffffp-1;
    f.z[0] = -1.0;
    f.code[0].opcode = CNOISE_OP_PUSH_COORD_X;
    f.code[1].opcode = CNOISE_OP_PUSH_COORD_Y;
    f.code[2].opcode = CNOISE_OP_MUL;
    f.code[3].opcode = CNOISE_OP_PUSH_COORD_Z;
    f.code[4].opcode = CNOISE_OP_ADD;
    f.code[5].opcode = CNOISE_OP_RETURN;
    f.program.instruction_count = 6;
    f.program.max_stack = 2;
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == 0);

    initialize(&f);
    binary_program(&f, CNOISE_OP_MUL);
    f.x[0] = DBL_MIN;
    f.y[0] = 0.5;
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == UINT64_C(0x0008000000000000));

    initialize(&f);
    binary_program(&f, CNOISE_OP_DIV);
    f.x[0] = 1.0;
    f.y[0] = 0.0;
    f.x[1] = -1.0;
    f.y[1] = 0.0;
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == UINT64_C(0x7ff0000000000000));
    CHECK(raw_bits(f.results[1]) == UINT64_C(0xfff0000000000000));
}

static void test_special_values(void) {
    const uint64_t values[] = {
        UINT64_C(0), UINT64_C(0x8000000000000000),
        UINT64_C(1), UINT64_C(0x8000000000000001),
        UINT64_C(0x7ff0000000000000), UINT64_C(0xfff0000000000000),
        UINT64_C(0x7ff8000000001234), UINT64_C(0xfff8000000005678)
    };
    fixture f;
    size_t i;

    initialize(&f);
    f.code[1].opcode = CNOISE_OP_NEG;
    f.code[2].opcode = CNOISE_OP_RETURN;
    f.program.instruction_count = 3;
    for (i = 0; i < ARRAY_COUNT(values); ++i) {
        f.x[i] = from_bits(values[i]);
    }
    CHECK(evaluate(&f) == CNOISE_OK);
    for (i = 0; i < ARRAY_COUNT(values); ++i) {
        CHECK(raw_bits(f.results[i])
            == (values[i] ^ UINT64_C(0x8000000000000000)));
    }

    f.code[1].opcode = CNOISE_OP_ABS;
    CHECK(evaluate(&f) == CNOISE_OK);
    for (i = 0; i < ARRAY_COUNT(values); ++i) {
        CHECK(raw_bits(f.results[i])
            == (values[i] & UINT64_C(0x7fffffffffffffff)));
    }

    initialize(&f);
    binary_program(&f, CNOISE_OP_MIN);
    f.x[0] = 0.0;
    f.y[0] = from_bits(UINT64_C(0x8000000000000000));
    f.x[1] = f.y[0];
    f.y[1] = 0.0;
    f.x[2] = from_bits(UINT64_C(0x7ff8000000001234));
    f.y[2] = 7.0;
    f.x[3] = 7.0;
    f.y[3] = from_bits(UINT64_C(0xfff8000000005678));
    f.x[4] = f.x[2];
    f.y[4] = f.y[3];
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == UINT64_C(0x8000000000000000));
    CHECK(raw_bits(f.results[1]) == UINT64_C(0x8000000000000000));
    CHECK(raw_bits(f.results[2]) == raw_bits(f.x[2]));
    CHECK(raw_bits(f.results[3]) == raw_bits(f.y[3]));
    CHECK(raw_bits(f.results[4]) == raw_bits(f.x[4]));

    f.code[2].opcode = CNOISE_OP_MAX;
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(raw_bits(f.results[0]) == 0);
    CHECK(raw_bits(f.results[1]) == 0);
    CHECK(raw_bits(f.results[2]) == raw_bits(f.x[2]));
    CHECK(raw_bits(f.results[3]) == raw_bits(f.y[3]));
    CHECK(raw_bits(f.results[4]) == raw_bits(f.x[4]));
}

static void test_clamp(void) {
    fixture f;
    size_t i;

    initialize(&f);
    f.constants[0] = -1.0;
    f.constants[1] = 2.0;
    f.code[0].opcode = CNOISE_OP_PUSH_COORD_X;
    f.code[1].opcode = CNOISE_OP_PUSH_CONST;
    f.code[2].opcode = CNOISE_OP_PUSH_CONST;
    f.code[2].operand = 1;
    f.code[3].opcode = CNOISE_OP_CLAMP;
    f.code[4].opcode = CNOISE_OP_RETURN;
    f.program.instruction_count = 5;
    f.program.max_stack = 3;
    CHECK(evaluate(&f) == CNOISE_OK);
    for (i = 0; i < ARRAY_COUNT(f.results); ++i) {
        double expected = f.x[i];
        if (expected < -1.0) {
            expected = -1.0;
        }
        if (expected > 2.0) {
            expected = 2.0;
        }
        CHECK(raw_bits(f.results[i]) == raw_bits(expected));
    }

    /* Reversed bounds retain the specified min(max()) ordering. */
    f.constants[0] = 2.0;
    f.constants[1] = -1.0;
    CHECK(evaluate(&f) == CNOISE_OK);
    CHECK(f.results[0] == -1.0);
}

static void expect_invalid(fixture *f, int32_t expected) {
    uint32_t depth = UINT32_MAX;
    CHECK(cnoise_verify_program(&f->program, &depth) == expected);
    CHECK(depth == UINT32_MAX);
    CHECK(evaluate(f) == expected);
    check_unchanged(f);
}

static void test_invalid_programs(void) {
    fixture f;
    const uint32_t underflow_ops[] = {
        CNOISE_OP_ADD, CNOISE_OP_SUB, CNOISE_OP_MUL,
        CNOISE_OP_DIV, CNOISE_OP_MIN, CNOISE_OP_MAX,
        CNOISE_OP_NEG, CNOISE_OP_ABS, CNOISE_OP_CLAMP,
        CNOISE_OP_RETURN
    };
    size_t i;

    for (i = 0; i < ARRAY_COUNT(underflow_ops); ++i) {
        initialize(&f);
        f.code[0].opcode = underflow_ops[i];
        expect_invalid(&f, CNOISE_INVALID_PROGRAM);
    }

    initialize(&f);
    f.code[0].opcode = UINT32_MAX;
    expect_invalid(&f, CNOISE_UNSUPPORTED_OPERATION);

    initialize(&f);
    f.code[0].operand = 1;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.code[0].opcode = CNOISE_OP_PUSH_CONST;
    f.code[0].operand = f.program.constant_count;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.instruction_count = 1;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.instruction_count = 3;
    f.code[2].opcode = CNOISE_OP_RETURN;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.code[1].opcode = CNOISE_OP_PUSH_COORD_Y;
    f.code[2].opcode = CNOISE_OP_RETURN;
    f.program.instruction_count = 3;
    f.program.max_stack = 2;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.max_stack = 2;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.max_stack = 0;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.max_stack = CNOISE_MAX_STACK + 1;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.program_version++;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.instruction_count = 0;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.instruction_count = CNOISE_MAX_INSTRUCTIONS + 1;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.constant_count = CNOISE_MAX_CONSTANTS + 1;
    expect_invalid(&f, CNOISE_INVALID_PROGRAM);

    initialize(&f);
    f.program.struct_size--;
    expect_invalid(&f, CNOISE_INVALID_ARGUMENT);

    initialize(&f);
    f.program.reserved = 1;
    expect_invalid(&f, CNOISE_INVALID_ARGUMENT);

    initialize(&f);
    f.program.instructions = NULL;
    expect_invalid(&f, CNOISE_INVALID_ARGUMENT);

    initialize(&f);
    f.program.constants = NULL;
    expect_invalid(&f, CNOISE_INVALID_ARGUMENT);

    initialize(&f);
    CHECK(cnoise_verify_program(&f.program, NULL) == CNOISE_INVALID_ARGUMENT);
    CHECK(cnoise_verify_program(&f.program, &f.code[0].operand)
        == CNOISE_INVALID_ARGUMENT);
    CHECK(f.code[0].operand == 0);
}

static void test_stack_limit(void) {
    cnoise_instruction code[2 * CNOISE_MAX_STACK + 2];
    fixture f;
    uint32_t i;
    uint32_t cursor = 0;
    uint32_t depth = 0;

    initialize(&f);
    memset(code, 0, sizeof(code));
    for (i = 0; i < CNOISE_MAX_STACK; ++i) {
        code[cursor++].opcode = CNOISE_OP_PUSH_COORD_X;
    }
    for (i = 1; i < CNOISE_MAX_STACK; ++i) {
        code[cursor++].opcode = CNOISE_OP_ADD;
    }
    code[cursor++].opcode = CNOISE_OP_RETURN;
    f.program.instructions = code;
    f.program.instruction_count = cursor;
    f.program.max_stack = CNOISE_MAX_STACK;
    CHECK(cnoise_verify_program(&f.program, &depth) == CNOISE_OK);
    CHECK(depth == CNOISE_MAX_STACK);
    CHECK(evaluate(&f) == CNOISE_BUFFER_TOO_SMALL);
    check_unchanged(&f);

    code[CNOISE_MAX_STACK].opcode = CNOISE_OP_PUSH_COORD_X;
    depth = UINT32_MAX;
    CHECK(cnoise_verify_program(&f.program, &depth) == CNOISE_INVALID_PROGRAM);
    CHECK(depth == UINT32_MAX);
}

static void test_buffers(void) {
    fixture f;

    initialize(&f);
    CHECK(cnoise_eval_batch(NULL, &f.batch, &f.output, &f.workspace)
        == CNOISE_INVALID_ARGUMENT);
    CHECK(cnoise_eval_batch(&f.program, NULL, &f.output, &f.workspace)
        == CNOISE_INVALID_ARGUMENT);
    CHECK(cnoise_eval_batch(&f.program, &f.batch, NULL, &f.workspace)
        == CNOISE_INVALID_ARGUMENT);
    CHECK(cnoise_eval_batch(&f.program, &f.batch, &f.output, NULL)
        == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    f.batch.struct_size--;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.output.reserved = 1;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.workspace.struct_size--;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.batch.point_count = CNOISE_MAX_BATCH_POINTS + 1;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.output.capacity = f.batch.point_count - 1;
    CHECK(evaluate(&f) == CNOISE_BUFFER_TOO_SMALL);
    check_unchanged(&f);

    initialize(&f);
    f.workspace.capacity = 0;
    CHECK(evaluate(&f) == CNOISE_BUFFER_TOO_SMALL);
    check_unchanged(&f);

    initialize(&f);
    f.batch.x = NULL;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.output.values = NULL;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.output.capacity = UINT64_MAX;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.workspace.capacity = UINT64_MAX;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.batch.x = f.results;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.workspace.values = f.results;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.workspace.values = f.x;
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    CHECK(f.x[0] == -3.0);
    check_unchanged(&f);

    initialize(&f);
    f.batch.point_count = 1;
    f.output.values = f.constants;
    f.output.capacity = ARRAY_COUNT(f.constants);
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    CHECK(f.constants[0] == 0.0);

    /* Unused mutable capacity is included in overlap validation. */
    initialize(&f);
    f.batch.point_count = 1;
    f.batch.x = &f.results[7];
    CHECK(evaluate(&f) == CNOISE_INVALID_ARGUMENT);
    check_unchanged(&f);

    initialize(&f);
    f.batch.point_count = 0;
    f.batch.x = NULL;
    f.batch.y = NULL;
    f.batch.z = NULL;
    f.output.values = NULL;
    f.output.capacity = 0;
    f.workspace.values = NULL;
    f.workspace.capacity = 0;
    CHECK(evaluate(&f) == CNOISE_OK);
    check_unchanged(&f);

    f.code[0].opcode = CNOISE_OP_ADD;
    CHECK(evaluate(&f) == CNOISE_INVALID_PROGRAM);
}

static void test_fp_environment(void) {
    fixture f;
    int original_rounding = fegetround();
    int changed;

    initialize(&f);
    CHECK(original_rounding == FE_TONEAREST);
    changed = fesetround(FE_DOWNWARD);
    CHECK(changed == 0);
    if (changed == 0) {
        CHECK(evaluate(&f) == CNOISE_UNSUPPORTED_FP_ENVIRONMENT);
        CHECK(fegetround() == FE_DOWNWARD);
        check_unchanged(&f);
    }
    CHECK(fesetround(original_rounding) == 0);

#if defined(_M_X64) || defined(__x86_64__)
    {
        unsigned int original = _mm_getcsr();
        _mm_setcsr(original | 0x8000u);
        CHECK(evaluate(&f) == CNOISE_UNSUPPORTED_FP_ENVIRONMENT);
        CHECK((_mm_getcsr() & 0x8000u) != 0);
        _mm_setcsr(original);
        check_unchanged(&f);

        _mm_setcsr(original | 0x0040u);
        CHECK(evaluate(&f) == CNOISE_UNSUPPORTED_FP_ENVIRONMENT);
        _mm_setcsr(original);
        check_unchanged(&f);

        /* Reject unmasked exceptions before division or other arithmetic. */
        _mm_setcsr((original & ~0x3fu) & ~0x0200u);
        CHECK(evaluate(&f) == CNOISE_UNSUPPORTED_FP_ENVIRONMENT);
        _mm_setcsr(original);
        check_unchanged(&f);
    }
#endif

    CHECK(evaluate(&f) == CNOISE_OK);
}

int main(void) {
    test_layouts();
    test_coordinates_and_constants();
    test_arithmetic();
    test_special_values();
    test_clamp();
    test_invalid_programs();
    test_stack_limit();
    test_buffers();
    test_fp_environment();

    printf("C-Noise native core: %u checks, %u failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
