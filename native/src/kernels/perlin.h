#ifndef CNOISE_PERLIN_H
#define CNOISE_PERLIN_H

#include <stdint.h>

/*
 * Internal reference kernels for Minecraft 26.3.
 * These structures are not part of the public ABI or a serialized format.
 *
 * The permutation and offsets must be copied from the same initialized
 * Minecraft GradientNoise instance. Random-number generation stays in Java.
 * State is immutable throughout evaluation.
 */
typedef struct cnoise_perlin_state {
    uint8_t permutation[256];
    double offset_x;
    double offset_y;
    double offset_z;
} cnoise_perlin_state;

typedef struct cnoise_perlin_layer {
    const cnoise_perlin_state *state;
    double frequency;
    float amplitude;
} cnoise_perlin_layer;

/*
 * Preconditions, enforced by the caller before invoking these internal
 * functions:
 *
 * - Every state pointer is valid and points to initialized readable storage.
 * - Each permutation contains every integer from 0 through 255 exactly once.
 * - Offsets are finite and in [0, 256).
 * - Coordinates are finite and their absolute values do not exceed 2^40.
 *   This conservative domain also bounds wrapped coordinates safely inside
 *   int32_t before the floor-to-integer conversion.
 * - Minecraft DEBUG_ENABLE_FARLANDS is false.
 * - Floating-point operations use round-to-nearest, ties-to-even, with
 *   gradual underflow. Exceptions are masked.
 * - Compilation must disable fast-math, reassociation and contraction/FMA,
 *   and must not evaluate float expressions with excess precision.
 *
 * Unsupported inputs must use the Java path, not be clamped or silently
 * normalized. NaN/infinity inputs and Farlands debug mode are not supported
 * by this initial kernel.
 *
 * This function matches PerlinNoise.get(double, double, double), not the
 * volume, derivative, two-coordinate or smeared sampling entry points.
 * It performs no allocation and retains no pointers.
 */
float cnoise_perlin_sample(
    const cnoise_perlin_state *state,
    double x,
    double y,
    double z
);

/*
 * Matches SmearedPerlinNoise.get(double, double, double), not addToVolume.
 * The state and coordinate preconditions above apply. Additionally,
 * fudge_y_scale must be finite and in [2^-30, 2^24]. This range keeps
 * computeFudgeY's floor result inside Java's int domain. Some codec-valid
 * BlendedNoise settings exceed this supported range and must use Java.
 * Copy the scale from the initialized Java noise; do not reconstruct it
 * with reassociated multiplications. Unsupported scales use Java.
 */
float cnoise_smeared_perlin_sample(
    const cnoise_perlin_state *state,
    double fudge_y_scale,
    double x,
    double y,
    double z
);

/*
 * Matches NoiseStack's ordered scalar accumulation for Perlin-only layers.
 *
 * Additional preconditions:
 * - layers points to layer_count readable, immutable entries, or may be
 *   null when layer_count is zero.
 * - Each frequency and amplitude is finite.
 * - Every scaled coordinate (coordinate * frequency) satisfies the sample
 *   domain above. Validate products, not just the unscaled coordinates.
 * - Contributions and intermediate accumulated results remain finite.
 *
 * Layer order must match Minecraft. Zero-amplitude layers are not skipped.
 * An empty stack returns positive zero without reading layers or sampling.
 * Distinct calls may share immutable state.
 */
float cnoise_perlin_stack_sample(
    const cnoise_perlin_layer *layers,
    uint32_t layer_count,
    double x,
    double y,
    double z
);

#endif
