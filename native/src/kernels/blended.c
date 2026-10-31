#include "blended.h"

float cnoise_smeared_stack_sample(
    const cnoise_smeared_stack *stack,
    double x,
    double y,
    double z
) {
    float result = 0.0f;
    uint32_t index;

    for (index = 0; index < stack->layer_count; ++index) {
        const cnoise_smeared_layer *layer = &stack->layers[index];
        double scaled_x = x * layer->frequency;
        double scaled_y = y * layer->frequency;
        double scaled_z = z * layer->frequency;
        float sample = cnoise_smeared_perlin_sample(
            layer->state, layer->fudge_y_scale,
            scaled_x, scaled_y, scaled_z);
        float contribution = layer->amplitude * sample;
        result = result + contribution;
    }

    return result;
}

float cnoise_blended_sample(
    const cnoise_blended_state *state,
    int32_t x,
    int32_t y,
    int32_t z
) {
    double main_x = (double)x * state->xz_main_multiplier;
    double main_y = (double)y * state->y_main_multiplier;
    double main_z = (double)z * state->xz_main_multiplier;
    float main_value = cnoise_smeared_stack_sample(
        &state->main, main_x, main_y, main_z);
    float alpha = main_value + 0.5f;
    double limit_x;
    double limit_y;
    double limit_z;
    float min_value;
    float max_value;
    float difference;
    float scaled_difference;

    if (alpha < 0.0f) {
        alpha = 0.0f;
    } else if (alpha > 1.0f) {
        alpha = 1.0f;
    }

    limit_x = (double)x * state->xz_multiplier;
    limit_y = (double)y * state->y_multiplier;
    limit_z = (double)z * state->xz_multiplier;

    /*
     * LerpFunction.Sampler returns the selected endpoint directly.
     * Interpolating at alpha == 0 or 1 would change rounding and could
     * evaluate a branch that Minecraft does not sample.
     */
    if (alpha == 0.0f) {
        return cnoise_smeared_stack_sample(
            &state->min_limit, limit_x, limit_y, limit_z);
    }
    if (alpha == 1.0f) {
        return cnoise_smeared_stack_sample(
            &state->max_limit, limit_x, limit_y, limit_z);
    }

    min_value = cnoise_smeared_stack_sample(
        &state->min_limit, limit_x, limit_y, limit_z);
    max_value = cnoise_smeared_stack_sample(
        &state->max_limit, limit_x, limit_y, limit_z);
    difference = max_value - min_value;
    scaled_difference = alpha * difference;
    return min_value + scaled_difference;
}
