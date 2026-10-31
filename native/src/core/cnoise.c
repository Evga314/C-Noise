#include "cnoise.h"

#include <stddef.h>
#include <stdint.h>
#include <float.h>
#include <fenv.h>
#include <string.h>

#if defined(_M_X64) || defined(__x86_64__)
    #include <xmmintrin.h>
#elif defined(__aarch64__) && (defined(__GNUC__) || defined(__clang__))
    /* FPCR is read below without changing the caller's environment. */
#else
    #error Unsupported target: floating-point environment checks are required.
#endif

#if defined(_MSC_VER)
    #define CNOISE_ALIGNOF(type) __alignof(type)
#else
    #define CNOISE_ALIGNOF(type) _Alignof(type)
#endif

_Static_assert(sizeof(void *) == 8, "A 64-bit target is required");
_Static_assert(sizeof(double) == 8, "binary64 storage is required");
_Static_assert(FLT_RADIX == 2 && DBL_MANT_DIG == 53
    && DBL_MAX_EXP == 1024 && DBL_MIN_EXP == -1021,
    "IEEE 754 binary64 is required");
_Static_assert(FLT_EVAL_METHOD == 0, "Excess floating-point precision is forbidden");
_Static_assert(sizeof(cnoise_instruction) == 8, "Unexpected instruction layout");
_Static_assert(sizeof(cnoise_program_view) == 40, "Unexpected program layout");
_Static_assert(sizeof(cnoise_batch_view) == 40, "Unexpected batch layout");
_Static_assert(sizeof(cnoise_result_view) == 24, "Unexpected result layout");
_Static_assert(sizeof(cnoise_workspace_view) == 24, "Unexpected workspace layout");
_Static_assert(offsetof(cnoise_program_view, instructions) == 8,
    "Unexpected instructions offset");
_Static_assert(offsetof(cnoise_program_view, constants) == 24,
    "Unexpected constants offset");
_Static_assert(offsetof(cnoise_batch_view, point_count) == 32,
    "Unexpected point count offset");

typedef struct memory_range {
    uintptr_t begin;
    uintptr_t end;
} memory_range;

/*
 * Integer address ranges allow overflow and overlap checks without pointer
 * arithmetic across unrelated C objects. They do not establish accessibility.
 */
static int make_range(
    const void *pointer,
    uint64_t count,
    uint64_t element_size,
    uint32_t alignment,
    memory_range *range
) {
    uintptr_t address;
    uint64_t bytes;

    range->begin = 0;
    range->end = 0;
    if (count == 0) {
        return 1;
    }
    if (pointer == NULL || element_size == 0 || alignment == 0) {
        return 0;
    }

    address = (uintptr_t)pointer;
    if (address % alignment != 0 || count > UINT64_MAX / element_size) {
        return 0;
    }
    bytes = count * element_size;
    if (bytes > UINTPTR_MAX - address) {
        return 0;
    }

    range->begin = address;
    range->end = address + (uintptr_t)bytes;
    return 1;
}

static int overlaps(memory_range a, memory_range b) {
    return a.begin < b.end && b.begin < a.end;
}

static int object_range(
    const void *pointer,
    uint64_t size,
    uint32_t alignment,
    memory_range *range
) {
    return make_range(pointer, 1, size, alignment, range);
}

static uint64_t double_bits(double value) {
    uint64_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static double bits_double(uint64_t bits) {
    double value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static int is_nan_bits(uint64_t bits) {
    return (bits & UINT64_C(0x7fffffffffffffff))
        > UINT64_C(0x7ff0000000000000);
}

static double java_min(double lhs, double rhs) {
    uint64_t left = double_bits(lhs);
    uint64_t right = double_bits(rhs);

    if (is_nan_bits(left)) {
        return lhs;
    }
    if (is_nan_bits(right)) {
        return rhs;
    }
    if (lhs == 0.0 && rhs == 0.0) {
        return bits_double(left | right);
    }
    return lhs <= rhs ? lhs : rhs;
}

static double java_max(double lhs, double rhs) {
    uint64_t left = double_bits(lhs);
    uint64_t right = double_bits(rhs);

    if (is_nan_bits(left)) {
        return lhs;
    }
    if (is_nan_bits(right)) {
        return rhs;
    }
    if (lhs == 0.0 && rhs == 0.0) {
        return bits_double(left & right);
    }
    return lhs >= rhs ? lhs : rhs;
}

static int supported_fp_environment(void) {
    if (fegetround() != FE_TONEAREST) {
        return 0;
    }

#if defined(_M_X64) || defined(__x86_64__)
    /*
     * Require masked exceptions, nearest rounding, and disabled DAZ/FTZ.
     * Exception status flags are deliberately ignored.
     */
    {
        unsigned int control = _mm_getcsr();
        const unsigned int exception_masks = 0x1f80u;
        const unsigned int disallowed = 0xe040u;
        return (control & exception_masks) == exception_masks
            && (control & disallowed) == 0;
    }
#else
    {
        uint64_t control;
        /*
         * Reject alternate handling, exception traps, non-nearest rounding,
         * flush-to-zero and default-NaN modes, including newer AH/FIZ bits.
         */
        const uint64_t disallowed = UINT64_C(0x07c09f07);
        __asm__ volatile("mrs %0, fpcr" : "=r"(control));
        return (control & disallowed) == 0;
    }
#endif
}

uint32_t CNOISE_CALL cnoise_abi_version(void) {
    return CNOISE_ABI_VERSION;
}

uint32_t CNOISE_CALL cnoise_layout_size(uint32_t layout_id) {
    switch (layout_id) {
        case CNOISE_LAYOUT_INSTRUCTION:
            return (uint32_t)sizeof(cnoise_instruction);
        case CNOISE_LAYOUT_PROGRAM:
            return (uint32_t)sizeof(cnoise_program_view);
        case CNOISE_LAYOUT_BATCH:
            return (uint32_t)sizeof(cnoise_batch_view);
        case CNOISE_LAYOUT_RESULT:
            return (uint32_t)sizeof(cnoise_result_view);
        case CNOISE_LAYOUT_WORKSPACE:
            return (uint32_t)sizeof(cnoise_workspace_view);
        default:
            return 0;
    }
}

uint32_t CNOISE_CALL cnoise_layout_alignment(uint32_t layout_id) {
    switch (layout_id) {
        case CNOISE_LAYOUT_INSTRUCTION:
            return (uint32_t)CNOISE_ALIGNOF(cnoise_instruction);
        case CNOISE_LAYOUT_PROGRAM:
            return (uint32_t)CNOISE_ALIGNOF(cnoise_program_view);
        case CNOISE_LAYOUT_BATCH:
            return (uint32_t)CNOISE_ALIGNOF(cnoise_batch_view);
        case CNOISE_LAYOUT_RESULT:
            return (uint32_t)CNOISE_ALIGNOF(cnoise_result_view);
        case CNOISE_LAYOUT_WORKSPACE:
            return (uint32_t)CNOISE_ALIGNOF(cnoise_workspace_view);
        default:
            return 0;
    }
}

static int32_t validate_program(
    const cnoise_program_view *program,
    uint32_t *depth_result,
    memory_range ranges[3]
) {
    uint32_t depth = 0;
    uint32_t maximum = 0;
    uint32_t index;

    if (!object_range(program, sizeof(*program),
            CNOISE_ALIGNOF(cnoise_program_view), &ranges[0])) {
        return CNOISE_INVALID_ARGUMENT;
    }
    if (program->struct_size != sizeof(*program) || program->reserved != 0) {
        return CNOISE_INVALID_ARGUMENT;
    }
    if (program->program_version != CNOISE_PROGRAM_VERSION
            || program->instruction_count == 0
            || program->instruction_count > CNOISE_MAX_INSTRUCTIONS
            || program->constant_count > CNOISE_MAX_CONSTANTS
            || program->max_stack == 0
            || program->max_stack > CNOISE_MAX_STACK) {
        return CNOISE_INVALID_PROGRAM;
    }
    if (!make_range(program->instructions, program->instruction_count,
            sizeof(cnoise_instruction), CNOISE_ALIGNOF(cnoise_instruction),
            &ranges[1])
            || !make_range(program->constants, program->constant_count,
                sizeof(double), CNOISE_ALIGNOF(double), &ranges[2])) {
        return CNOISE_INVALID_ARGUMENT;
    }

    for (index = 0; index < program->instruction_count; ++index) {
        cnoise_instruction instruction = program->instructions[index];

        if (instruction.opcode != CNOISE_OP_PUSH_CONST
                && instruction.operand != 0) {
            return CNOISE_INVALID_PROGRAM;
        }

        switch (instruction.opcode) {
            case CNOISE_OP_PUSH_CONST:
                if (instruction.operand >= program->constant_count) {
                    return CNOISE_INVALID_PROGRAM;
                }
                ++depth;
                break;

            case CNOISE_OP_PUSH_COORD_X:
            case CNOISE_OP_PUSH_COORD_Y:
            case CNOISE_OP_PUSH_COORD_Z:
                ++depth;
                break;

            case CNOISE_OP_ADD:
            case CNOISE_OP_SUB:
            case CNOISE_OP_MUL:
            case CNOISE_OP_DIV:
            case CNOISE_OP_MIN:
            case CNOISE_OP_MAX:
                if (depth < 2) {
                    return CNOISE_INVALID_PROGRAM;
                }
                --depth;
                break;

            case CNOISE_OP_NEG:
            case CNOISE_OP_ABS:
                if (depth < 1) {
                    return CNOISE_INVALID_PROGRAM;
                }
                break;

            case CNOISE_OP_CLAMP:
                if (depth < 3) {
                    return CNOISE_INVALID_PROGRAM;
                }
                depth -= 2;
                break;

            case CNOISE_OP_RETURN:
                if (depth != 1 || index != program->instruction_count - 1) {
                    return CNOISE_INVALID_PROGRAM;
                }
                --depth;
                break;

            default:
                return CNOISE_UNSUPPORTED_OPERATION;
        }

        if (depth > maximum) {
            maximum = depth;
        }
        if (maximum > CNOISE_MAX_STACK) {
            return CNOISE_INVALID_PROGRAM;
        }
    }

    if (program->instructions[program->instruction_count - 1].opcode
            != CNOISE_OP_RETURN || maximum != program->max_stack) {
        return CNOISE_INVALID_PROGRAM;
    }

    *depth_result = maximum;
    return CNOISE_OK;
}

int32_t CNOISE_CALL cnoise_verify_program(
    const cnoise_program_view *program,
    uint32_t *required_stack
) {
    memory_range program_ranges[3];
    memory_range destination;
    uint32_t depth;
    uint32_t index;
    int32_t status;

    if (!object_range(required_stack, sizeof(*required_stack),
            CNOISE_ALIGNOF(uint32_t), &destination)) {
        return CNOISE_INVALID_ARGUMENT;
    }
    status = validate_program(program, &depth, program_ranges);
    if (status != CNOISE_OK) {
        return status;
    }
    for (index = 0; index < 3; ++index) {
        if (overlaps(destination, program_ranges[index])) {
            return CNOISE_INVALID_ARGUMENT;
        }
    }
    *required_stack = depth;
    return CNOISE_OK;
}

/* Only called after full validation; no failure path can partially publish output. */
static void evaluate_validated(
    const cnoise_program_view *program,
    const cnoise_batch_view *batch,
    double *output,
    double *stack
) {
    uint64_t point;

    for (point = 0; point < batch->point_count; ++point) {
        uint32_t depth = 0;
        uint32_t index;

        for (index = 0; index < program->instruction_count; ++index) {
            cnoise_instruction instruction = program->instructions[index];
            double rhs;
            double lower;
            double upper;

            switch (instruction.opcode) {
                case CNOISE_OP_PUSH_CONST:
                    stack[depth++] = program->constants[instruction.operand];
                    break;
                case CNOISE_OP_PUSH_COORD_X:
                    stack[depth++] = batch->x[point];
                    break;
                case CNOISE_OP_PUSH_COORD_Y:
                    stack[depth++] = batch->y[point];
                    break;
                case CNOISE_OP_PUSH_COORD_Z:
                    stack[depth++] = batch->z[point];
                    break;
                case CNOISE_OP_ADD:
                    rhs = stack[--depth];
                    stack[depth - 1] = stack[depth - 1] + rhs;
                    break;
                case CNOISE_OP_SUB:
                    rhs = stack[--depth];
                    stack[depth - 1] = stack[depth - 1] - rhs;
                    break;
                case CNOISE_OP_MUL:
                    rhs = stack[--depth];
                    stack[depth - 1] = stack[depth - 1] * rhs;
                    break;
                case CNOISE_OP_DIV:
                    rhs = stack[--depth];
                    stack[depth - 1] = stack[depth - 1] / rhs;
                    break;
                case CNOISE_OP_MIN:
                    rhs = stack[--depth];
                    stack[depth - 1] = java_min(stack[depth - 1], rhs);
                    break;
                case CNOISE_OP_MAX:
                    rhs = stack[--depth];
                    stack[depth - 1] = java_max(stack[depth - 1], rhs);
                    break;
                case CNOISE_OP_NEG:
                    stack[depth - 1] = bits_double(
                        double_bits(stack[depth - 1])
                        ^ UINT64_C(0x8000000000000000));
                    break;
                case CNOISE_OP_ABS:
                    stack[depth - 1] = bits_double(
                        double_bits(stack[depth - 1])
                        & UINT64_C(0x7fffffffffffffff));
                    break;
                case CNOISE_OP_CLAMP:
                    upper = stack[--depth];
                    lower = stack[--depth];
                    stack[depth - 1] = java_min(
                        java_max(stack[depth - 1], lower), upper);
                    break;
                case CNOISE_OP_RETURN:
                    output[point] = stack[0];
                    break;
            }
        }
    }
}

int32_t CNOISE_CALL cnoise_eval_batch(
    const cnoise_program_view *program,
    const cnoise_batch_view *batch,
    const cnoise_result_view *output,
    const cnoise_workspace_view *workspace
) {
    /*
     * Read-only ranges: program, instructions, constants, three views,
     * and three coordinate arrays. Mutable ranges use declared capacities,
     * so even unused tails must not alias an input.
     */
    memory_range inputs[9];
    memory_range results;
    memory_range scratch;
    uint32_t depth;
    uint32_t index;
    int32_t status = validate_program(program, &depth, inputs);

    if (status != CNOISE_OK) {
        return status;
    }
    if (!object_range(batch, sizeof(*batch),
            CNOISE_ALIGNOF(cnoise_batch_view), &inputs[3])
            || !object_range(output, sizeof(*output),
                CNOISE_ALIGNOF(cnoise_result_view), &inputs[4])
            || !object_range(workspace, sizeof(*workspace),
                CNOISE_ALIGNOF(cnoise_workspace_view), &inputs[5])) {
        return CNOISE_INVALID_ARGUMENT;
    }
    if (batch->struct_size != sizeof(*batch) || batch->reserved != 0
            || output->struct_size != sizeof(*output) || output->reserved != 0
            || workspace->struct_size != sizeof(*workspace)
            || workspace->reserved != 0
            || batch->point_count > CNOISE_MAX_BATCH_POINTS) {
        return CNOISE_INVALID_ARGUMENT;
    }

    if (!supported_fp_environment()) {
        return CNOISE_UNSUPPORTED_FP_ENVIRONMENT;
    }
    if (batch->point_count == 0) {
        return CNOISE_OK;
    }
    if (output->capacity < batch->point_count || workspace->capacity < depth) {
        return CNOISE_BUFFER_TOO_SMALL;
    }

    if (!make_range(batch->x, batch->point_count, sizeof(double),
            CNOISE_ALIGNOF(double), &inputs[6])
            || !make_range(batch->y, batch->point_count, sizeof(double),
                CNOISE_ALIGNOF(double), &inputs[7])
            || !make_range(batch->z, batch->point_count, sizeof(double),
                CNOISE_ALIGNOF(double), &inputs[8])
            || !make_range(output->values, output->capacity, sizeof(double),
                CNOISE_ALIGNOF(double), &results)
            || !make_range(workspace->values, workspace->capacity, sizeof(double),
                CNOISE_ALIGNOF(double), &scratch)) {
        return CNOISE_INVALID_ARGUMENT;
    }

    if (overlaps(results, scratch)) {
        return CNOISE_INVALID_ARGUMENT;
    }
    for (index = 0; index < 9; ++index) {
        if (overlaps(results, inputs[index]) || overlaps(scratch, inputs[index])) {
            return CNOISE_INVALID_ARGUMENT;
        }
    }

    evaluate_validated(program, batch, output->values, workspace->values);
    return CNOISE_OK;
}
