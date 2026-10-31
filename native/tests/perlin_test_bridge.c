#include "../src/kernels/perlin.h"
#include "../src/kernels/blended.h"

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define TEST_EXPORT __declspec(dllexport)
#else
#define TEST_EXPORT __attribute__((visibility("default")))
#endif

/*
 * Test-only exports. The production library does not compile this file.
 * Java checks these layout values before allocating or passing structures.
 */
TEST_EXPORT uint64_t cnoise_test_perlin_layout(uint32_t field) {
    switch (field) {
        case 0: return sizeof(cnoise_perlin_state);
        case 1: return _Alignof(cnoise_perlin_state);
        case 2: return offsetof(cnoise_perlin_state, permutation);
        case 3: return offsetof(cnoise_perlin_state, offset_x);
        case 4: return offsetof(cnoise_perlin_state, offset_y);
        case 5: return offsetof(cnoise_perlin_state, offset_z);
        case 6: return sizeof(cnoise_perlin_layer);
        case 7: return _Alignof(cnoise_perlin_layer);
        case 8: return offsetof(cnoise_perlin_layer, state);
        case 9: return offsetof(cnoise_perlin_layer, frequency);
        case 10: return offsetof(cnoise_perlin_layer, amplitude);
        default: return UINT64_MAX;
    }
}

TEST_EXPORT float cnoise_test_perlin_sample(
    const cnoise_perlin_state *state,
    double x,
    double y,
    double z
) {
    return cnoise_perlin_sample(state, x, y, z);
}

TEST_EXPORT float cnoise_test_perlin_stack_sample(
    const cnoise_perlin_layer *layers,
    uint32_t layer_count,
    double x,
    double y,
    double z
) {
    return cnoise_perlin_stack_sample(layers, layer_count, x, y, z);
}

TEST_EXPORT uint64_t cnoise_test_blended_layout(uint32_t field) {
    switch (field) {
        case 0: return sizeof(cnoise_smeared_layer);
        case 1: return _Alignof(cnoise_smeared_layer);
        case 2: return offsetof(cnoise_smeared_layer, state);
        case 3: return offsetof(cnoise_smeared_layer, frequency);
        case 4: return offsetof(cnoise_smeared_layer, fudge_y_scale);
        case 5: return offsetof(cnoise_smeared_layer, amplitude);
        case 6: return sizeof(cnoise_smeared_stack);
        case 7: return _Alignof(cnoise_smeared_stack);
        case 8: return offsetof(cnoise_smeared_stack, layers);
        case 9: return offsetof(cnoise_smeared_stack, layer_count);
        case 10: return sizeof(cnoise_blended_state);
        case 11: return _Alignof(cnoise_blended_state);
        case 12: return offsetof(cnoise_blended_state, min_limit);
        case 13: return offsetof(cnoise_blended_state, max_limit);
        case 14: return offsetof(cnoise_blended_state, main);
        case 15: return offsetof(cnoise_blended_state, xz_multiplier);
        case 16: return offsetof(cnoise_blended_state, y_multiplier);
        case 17: return offsetof(cnoise_blended_state, xz_main_multiplier);
        case 18: return offsetof(cnoise_blended_state, y_main_multiplier);
        default: return UINT64_MAX;
    }
}

TEST_EXPORT float cnoise_test_smeared_sample(
    const cnoise_perlin_state *state,
    double fudge_y_scale,
    double x,
    double y,
    double z
) {
    return cnoise_smeared_perlin_sample(state, fudge_y_scale, x, y, z);
}

TEST_EXPORT float cnoise_test_smeared_stack_sample(
    const cnoise_smeared_stack *stack,
    double x,
    double y,
    double z
) {
    return cnoise_smeared_stack_sample(stack, x, y, z);
}

TEST_EXPORT float cnoise_test_blended_sample(
    const cnoise_blended_state *state,
    int32_t x,
    int32_t y,
    int32_t z
) {
    return cnoise_blended_sample(state, x, y, z);
}
