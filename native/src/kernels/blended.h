#ifndef CNOISE_BLENDED_H
#define CNOISE_BLENDED_H

#include "perlin.h"

#include <stdint.h>

/*
 * Internal scalar representation, not a public ABI or serialized format.
 * Copy retained NoiseStack layers in their actual Java iteration order.
 * All pointed-to storage must remain readable and immutable during a call.
 */
typedef struct cnoise_smeared_layer {
    const cnoise_perlin_state *state;
    double frequency;
    double fudge_y_scale;
    float amplitude;
} cnoise_smeared_layer;

typedef struct cnoise_smeared_stack {
    const cnoise_smeared_layer *layers;
    uint32_t layer_count;
} cnoise_smeared_stack;

typedef struct cnoise_blended_state {
    cnoise_smeared_stack min_limit;
    cnoise_smeared_stack max_limit;
    cnoise_smeared_stack main;
    double xz_multiplier;
    double y_multiplier;
    double xz_main_multiplier;
    double y_main_multiplier;
} cnoise_blended_state;

/*
 * Matches NoiseStack.get for SmearedPerlinNoise-only layers.
 *
 * The floating-point environment, permutation and offset requirements in
 * perlin.h apply. Each fudge_y_scale must satisfy the smeared kernel's
 * supported domain. Frequencies, amplitudes and input coordinates are
 * finite. Every coordinate * frequency must be finite and within +/-2^40.
 * Contributions and intermediate sums must remain finite.
 *
 * layers may be null only when layer_count is zero. An empty stack returns
 * positive zero. Zero-amplitude layers are evaluated, not skipped.
 * Unsupported inputs must be rejected by the caller in favor of Java.
 */
float cnoise_smeared_stack_sample(
    const cnoise_smeared_stack *stack,
    double x,
    double y,
    double z
);

/*
 * Matches BlendedNoise.compileSampler(RandomSource).sampleValue for the
 * supplied integer block coordinates, not the volume sampling entry point.
 *
 * Copy multipliers from the compiled Java sampler, preserving the separate
 * coordinate multiplication and per-layer frequency multiplication.
 * All multipliers must be finite; all coordinate products and all three
 * stacks must satisfy the contract above, including inactive branches.
 *
 * Evaluates main first, adds 0.5f and clamps to [0, 1]. At either endpoint,
 * only the selected limit stack is sampled. Otherwise min is sampled before
 * max, then float interpolation preserves Java's intermediate rounding.
 *
 * This kernel does not validate inputs, allocate, retain pointers or alter
 * the floating-point environment. It is not an exported FFM boundary.
 */
float cnoise_blended_sample(
    const cnoise_blended_state *state,
    int32_t x,
    int32_t y,
    int32_t z
);

#endif
