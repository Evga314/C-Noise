#include "perlin.h"

#include <float.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>

_Static_assert(sizeof(float) == 4, "binary32 storage is required");
_Static_assert(sizeof(double) == 8, "binary64 storage is required");
_Static_assert(FLT_RADIX == 2 && FLT_MANT_DIG == 24
    && FLT_MAX_EXP == 128 && FLT_MIN_EXP == -125,
    "IEEE 754 binary32 is required");
_Static_assert(DBL_MANT_DIG == 53
    && DBL_MAX_EXP == 1024 && DBL_MIN_EXP == -1021,
    "IEEE 754 binary64 is required");
_Static_assert(FLT_EVAL_METHOD == 0,
    "Excess floating-point precision is forbidden");

/*
 * Minecraft 26.3 GradientNoise.GRADIENT, including its repeated entries.
 * Multiplication by zero is intentional: replacing the dot product with
 * selected additions would change signed-zero semantics.
 */
static const int8_t gradients[16][3] = {
    { 1,  1,  0},
    {-1,  1,  0},
    { 1, -1,  0},
    {-1, -1,  0},
    { 1,  0,  1},
    {-1,  0,  1},
    { 1,  0, -1},
    {-1,  0, -1},
    { 0,  1,  1},
    { 0, -1,  1},
    { 0,  1, -1},
    { 0, -1, -1},
    { 1,  1,  0},
    { 0, -1,  1},
    {-1,  1,  0},
    { 0, -1, -1}
};

static double wrap_coordinate(double value) {
    /* Math.nextDown(16777216.0), not 16777216.0 itself. */
    const double half_round_off = 0x1.fffffffffffffp+23;
    const double round_off = 33554432.0;

    if (value >= -half_round_off && value < half_round_off) {
        return value;
    }

    {
        double quotient = value / round_off;
        double shifted = quotient + 0.5;
        double periods = floor(shifted);
        double displacement = periods * round_off;
        return value - displacement;
    }
}

static uint32_t permute(
    const cnoise_perlin_state *state,
    uint32_t index
) {
    return state->permutation[index & UINT32_C(255)];
}

static float gradient_dot(uint32_t hash, float x, float y, float z) {
    const int8_t *gradient = gradients[hash & UINT32_C(15)];
    float gx = (float)gradient[0] * x;
    float gy = (float)gradient[1] * y;
    float xy = gx + gy;
    float gz = (float)gradient[2] * z;
    return xy + gz;
}

static float smoothstep(float value) {
    float square = value * value;
    float cube = square * value;
    float six = value * 6.0f;
    float shifted = six - 15.0f;
    float product = value * shifted;
    float polynomial = product + 10.0f;
    return cube * polynomial;
}

static float lerp(float delta, float start, float end) {
    float difference = end - start;
    float scaled = delta * difference;
    return start + scaled;
}

static float lerp2(
    float dx,
    float dy,
    float v00,
    float v10,
    float v01,
    float v11
) {
    float lower = lerp(dx, v00, v10);
    float upper = lerp(dx, v01, v11);
    return lerp(dy, lower, upper);
}

static float sample_and_lerp(
    const cnoise_perlin_state *state,
    int32_t cell_x,
    int32_t cell_y,
    int32_t cell_z,
    float x,
    float y,
    float z,
    float interpolation_y
) {
    /*
     * Only the low eight bits reach the permutation table. Unsigned
     * arithmetic preserves those bits without C signed-overflow hazards.
     */
    uint32_t ix = (uint32_t)cell_x;
    uint32_t iy = (uint32_t)cell_y;
    uint32_t iz = (uint32_t)cell_z;
    uint32_t px0 = permute(state, ix);
    uint32_t px1 = permute(state, ix + UINT32_C(1));
    uint32_t p00 = permute(state, px0 + iy);
    uint32_t p01 = permute(state, px0 + iy + UINT32_C(1));
    uint32_t p10 = permute(state, px1 + iy);
    uint32_t p11 = permute(state, px1 + iy + UINT32_C(1));
    float xm1 = x - 1.0f;
    float ym1 = y - 1.0f;
    float zm1 = z - 1.0f;

    float v000 = gradient_dot(permute(state, p00 + iz), x, y, z);
    float v100 = gradient_dot(permute(state, p10 + iz), xm1, y, z);
    float v010 = gradient_dot(permute(state, p01 + iz), x, ym1, z);
    float v110 = gradient_dot(permute(state, p11 + iz), xm1, ym1, z);
    float v001 = gradient_dot(
        permute(state, p00 + iz + UINT32_C(1)), x, y, zm1);
    float v101 = gradient_dot(
        permute(state, p10 + iz + UINT32_C(1)), xm1, y, zm1);
    float v011 = gradient_dot(
        permute(state, p01 + iz + UINT32_C(1)), x, ym1, zm1);
    float v111 = gradient_dot(
        permute(state, p11 + iz + UINT32_C(1)), xm1, ym1, zm1);

    float dx = smoothstep(x);
    float dy = smoothstep(interpolation_y);
    float dz = smoothstep(z);
    float lower = lerp2(dx, dy, v000, v100, v010, v110);
    float upper = lerp2(dx, dy, v001, v101, v011, v111);
    return lerp(dz, lower, upper);
}

/*
 * Internal kernel: the caller must validate the state, coordinate domain
 * and floating-point environment described in perlin.h before entry.
 * This is not an exported FFM boundary.
 */
float cnoise_perlin_sample(
    const cnoise_perlin_state *state,
    double x,
    double y,
    double z
) {
    double shifted_x = wrap_coordinate(x) + state->offset_x;
    double shifted_y = wrap_coordinate(y) + state->offset_y;
    double shifted_z = wrap_coordinate(z) + state->offset_z;
    int32_t cell_x = (int32_t)floor(shifted_x);
    int32_t cell_y = (int32_t)floor(shifted_y);
    int32_t cell_z = (int32_t)floor(shifted_z);
    float local_x = (float)(shifted_x - (double)cell_x);
    float local_y = (float)(shifted_y - (double)cell_y);
    float local_z = (float)(shifted_z - (double)cell_z);

    return sample_and_lerp(
        state, cell_x, cell_y, cell_z, local_x, local_y, local_z, local_y);
}

float cnoise_smeared_perlin_sample(
    const cnoise_perlin_state *state,
    double fudge_y_scale,
    double x,
    double y,
    double z
) {
    double shifted_x = wrap_coordinate(x) + state->offset_x;
    double shifted_y = wrap_coordinate(y) + state->offset_y;
    double shifted_z = wrap_coordinate(z) + state->offset_z;
    int32_t cell_x = (int32_t)floor(shifted_x);
    int32_t cell_y = (int32_t)floor(shifted_y);
    int32_t cell_z = (int32_t)floor(shifted_z);
    float local_x = (float)(shifted_x - (double)cell_x);
    double local_y = shifted_y - (double)cell_y;
    float local_z = (float)(shifted_z - (double)cell_z);
    /* Minecraft compares the original, unwrapped Y against the fraction. */
    double limited_y = y >= 0.0 && y < local_y ? y : local_y;
    double quotient = limited_y / fudge_y_scale;
    /* The epsilon is the double widening of Java's 1.0e-7f. */
    double biased = quotient + (double)1.0e-7f;
    double steps = floor(biased);
    double fudge_y = steps * fudge_y_scale;
    float gradient_y = (float)(local_y - fudge_y);

    return sample_and_lerp(
        state, cell_x, cell_y, cell_z,
        local_x, gradient_y, local_z, (float)local_y);
}

float cnoise_perlin_stack_sample(
    const cnoise_perlin_layer *layers,
    uint32_t layer_count,
    double x,
    double y,
    double z
) {
    float result = 0.0f;
    uint32_t index;

    for (index = 0; index < layer_count; ++index) {
        const cnoise_perlin_layer *layer = &layers[index];
        double scaled_x = x * layer->frequency;
        double scaled_y = y * layer->frequency;
        double scaled_z = z * layer->frequency;
        float sample = cnoise_perlin_sample(
            layer->state, scaled_x, scaled_y, scaled_z);
        float contribution = layer->amplitude * sample;
        result = result + contribution;
    }

    return result;
}
