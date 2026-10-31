#ifndef CNOISE_H
#define CNOISE_H

#include <stdint.h>

#if defined(_WIN32)
    #if defined(CNOISE_BUILD)
        #define CNOISE_API __declspec(dllexport)
    #else
        #define CNOISE_API __declspec(dllimport)
    #endif
    #define CNOISE_CALL __cdecl
#else
    #define CNOISE_API __attribute__((visibility("default")))
    #define CNOISE_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Initial, experimental ABI. Any incompatible change to these layouts,
 * operation semantics or function signatures must increment the ABI version.
 *
 * Only 64-bit targets with IEEE 754 binary64 doubles are supported.
 * Structs use the platform's natural C alignment; packed structs are forbidden.
 * Java must check the ABI version and all layout sizes before evaluation.
 * This is an in-process interface, not a serialized program format.
 */
#define CNOISE_ABI_VERSION UINT32_C(1)
#define CNOISE_PROGRAM_VERSION UINT32_C(1)

#define CNOISE_MAX_INSTRUCTIONS UINT32_C(65536)
#define CNOISE_MAX_CONSTANTS UINT32_C(65536)
#define CNOISE_MAX_STACK UINT32_C(1024)
#define CNOISE_MAX_BATCH_POINTS UINT64_C(65536)

/* Fixed-width integers, rather than C enums, are used across the ABI. */
#define CNOISE_OK INT32_C(0)
#define CNOISE_NEED_JAVA INT32_C(1)
#define CNOISE_INVALID_ARGUMENT INT32_C(2)
#define CNOISE_INVALID_PROGRAM INT32_C(3)
#define CNOISE_BUFFER_TOO_SMALL INT32_C(4)
#define CNOISE_UNSUPPORTED_OPERATION INT32_C(5)
#define CNOISE_INTERNAL_ERROR INT32_C(6)
#define CNOISE_UNSUPPORTED_FP_ENVIRONMENT INT32_C(7)

/*
 * NEED_JAVA is reserved for the resumable batch-callback protocol.
 * This initial arithmetic VM does not emit it or accept callback opcodes.
 */
#define CNOISE_OP_PUSH_CONST UINT32_C(1)
#define CNOISE_OP_PUSH_COORD_X UINT32_C(2)
#define CNOISE_OP_PUSH_COORD_Y UINT32_C(3)
#define CNOISE_OP_PUSH_COORD_Z UINT32_C(4)
#define CNOISE_OP_ADD UINT32_C(5)
#define CNOISE_OP_SUB UINT32_C(6)
#define CNOISE_OP_MUL UINT32_C(7)
#define CNOISE_OP_DIV UINT32_C(8)
#define CNOISE_OP_MIN UINT32_C(9)
#define CNOISE_OP_MAX UINT32_C(10)
#define CNOISE_OP_NEG UINT32_C(11)
#define CNOISE_OP_ABS UINT32_C(12)
#define CNOISE_OP_CLAMP UINT32_C(13)
#define CNOISE_OP_RETURN UINT32_C(14)

/*
 * PUSH_CONST uses operand as an index into constants.
 * Every other instruction requires operand == 0.
 *
 * Binary operations pop rhs, then lhs, and push lhs OP rhs.
 * CLAMP consumes value, lower, upper in that push order, and computes
 * min(max(value, lower), upper), using the MIN/MAX semantics below.
 * RETURN must be the last instruction and must consume the only stack value.
 *
 * MIN/MAX follow Java Math.min/Math.max: NaN propagates and signed zero
 * is significant. NEG changes the sign bit. ABS clears the sign bit.
 * Arithmetic preserves evaluation order, with no reassociation or FMA.
 * Raw NaN parity still requires differential verification on the target JVM.
 */
typedef struct cnoise_instruction {
    uint32_t opcode;
    uint32_t operand;
} cnoise_instruction;

/*
 * Instructions and constants are borrowed, immutable arrays.
 * max_stack is the exact maximum stack depth, not a workspace capacity.
 * reserved must be zero.
 */
typedef struct cnoise_program_view {
    uint32_t struct_size;
    uint32_t program_version;
    const cnoise_instruction *instructions;
    uint32_t instruction_count;
    uint32_t max_stack;
    const double *constants;
    uint32_t constant_count;
    uint32_t reserved;
} cnoise_program_view;

/*
 * Coordinates are structure-of-arrays doubles. Each coordinate array has
 * point_count readable elements. Empty batches may have null array pointers.
 */
typedef struct cnoise_batch_view {
    uint32_t struct_size;
    uint32_t reserved;
    const double *x;
    const double *y;
    const double *z;
    uint64_t point_count;
} cnoise_batch_view;

/* Capacity is measured in doubles, not bytes. */
typedef struct cnoise_result_view {
    uint32_t struct_size;
    uint32_t reserved;
    double *values;
    uint64_t capacity;
} cnoise_result_view;

/*
 * Caller-owned, per-call scratch memory. The scalar reference VM requires
 * max_stack doubles, independent of batch size. No allocation occurs in C.
 */
typedef struct cnoise_workspace_view {
    uint32_t struct_size;
    uint32_t reserved;
    double *values;
    uint64_t capacity;
} cnoise_workspace_view;

#define CNOISE_LAYOUT_INSTRUCTION UINT32_C(1)
#define CNOISE_LAYOUT_PROGRAM UINT32_C(2)
#define CNOISE_LAYOUT_BATCH UINT32_C(3)
#define CNOISE_LAYOUT_RESULT UINT32_C(4)
#define CNOISE_LAYOUT_WORKSPACE UINT32_C(5)

CNOISE_API uint32_t CNOISE_CALL cnoise_abi_version(void);

/* Unknown layout IDs return zero. */
CNOISE_API uint32_t CNOISE_CALL cnoise_layout_size(uint32_t layout_id);
CNOISE_API uint32_t CNOISE_CALL cnoise_layout_alignment(uint32_t layout_id);

/*
 * Validates the entire linear program, including stack effects, operands,
 * limits, reserved fields and the terminal RETURN.
 * required_stack must point to a writable uint32_t and must not overlap
 * the program or its arrays. It is written only on success.
 */
CNOISE_API int32_t CNOISE_CALL cnoise_verify_program(
    const cnoise_program_view *program,
    uint32_t *required_stack
);

/*
 * Synchronous, reentrant batch evaluation. No Java objects, callbacks,
 * global mutable state, retained pointers or ownership transfers.
 *
 * All views must be non-null, correctly aligned and have struct_size equal
 * to the corresponding cnoise_layout_size result. Reserved fields are zero.
 * Arrays must remain alive and unmodified until the function returns.
 * Coordinate arrays may alias each other. Output and scratch storage must
 * not overlap one another, any input array, or any of the view structures.
 * Distinct concurrent calls must have distinct output and scratch storage.
 *
 * Validation is repeated at the native boundary before computation.
 * Failure leaves output unchanged; scratch contents are unspecified.
 * Successful empty batches do not access coordinate, output or scratch
 * arrays, but still validate the program and view headers.
 *
 * Requires round-to-nearest, ties-to-even and gradual underflow. Unsupported
 * floating-point modes are rejected, not silently changed.
 *
 * As with any C ABI, sizes cannot prove that an arbitrary pointer is valid.
 * The FFM bridge is responsible for allocation bounds, lifetime, alignment,
 * disjoint mutable buffers, and preventing concurrent mutation or closure.
 */
CNOISE_API int32_t CNOISE_CALL cnoise_eval_batch(
    const cnoise_program_view *program,
    const cnoise_batch_view *batch,
    const cnoise_result_view *output,
    const cnoise_workspace_view *workspace
);

#ifdef __cplusplus
}
#endif

#endif
