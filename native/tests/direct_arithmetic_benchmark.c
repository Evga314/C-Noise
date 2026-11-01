#include <math.h>
#include <stdint.h>

#if defined(_WIN32)
#define BENCH_EXPORT __declspec(dllexport)
#else
#define BENCH_EXPORT __attribute__((visibility("default")))
#endif

/*
 * Experimental kernel only; not part of the production C-Noise ABI.
 * Только экспериментальное ядро; не входит в производственный ABI C-Noise.
 *
 * Match Java min/max for signed zero and propagate NaNs rather than using
 * the C fmin/fmax rule that selects the non-NaN operand.
 * Сохраняем семантику Java для знакового нуля и распространения NaN,
 * а не правило C fmin/fmax, выбирающее операнд, отличный от NaN.
 */
static double java_min(double a, double b)
{
    if (isnan(a)) {
        return a;
    }
    if (isnan(b)) {
        return b;
    }
    if (a == 0.0 && b == 0.0) {
        return signbit(a) || signbit(b) ? -0.0 : 0.0;
    }
    return a <= b ? a : b;
}

static double java_max(double a, double b)
{
    if (isnan(a)) {
        return a;
    }
    if (isnan(b)) {
        return b;
    }
    if (a == 0.0 && b == 0.0) {
        return !signbit(a) || !signbit(b) ? 0.0 : -0.0;
    }
    return a >= b ? a : b;
}

/*
 * One call evaluates a whole batch, with the same operation order as
 * ThreeWayBenchmark.optimizedJavaBatch. No allocation or VM dispatch.
 * Один вызов вычисляет пакет с тем же порядком операций, что и
 * ThreeWayBenchmark.optimizedJavaBatch. Без аллокаций и диспетчеризации VM.
 *
 * Buffers must contain count doubles; output must not overlap inputs.
 * Буферы содержат count значений double; выход не пересекается со входами.
 */
BENCH_EXPORT int32_t cnoise_benchmark_direct_batch(
        const double *xs,
        const double *ys,
        const double *zs,
        double *output,
        uint64_t count)
{
    if (count == 0) {
        return 0;
    }
    if (xs == 0 || ys == 0 || zs == 0 || output == 0) {
        return 1;
    }

    for (uint64_t index = 0; index < count; ++index) {
        double x = xs[index];
        double y = ys[index];
        double z = zs[index];

        double a = x * 0.125;
        double b = y * 0.25;
        double cc = a + b;

        double d = z * 0.0625;
        double e = x * 0.03125;
        double f = d - e;
        double g = cc - f;
        double h = fabs(g);
        double i = h + 0.5;

        double j = y * 0.015625;
        double k = j + 1.5;
        double l = i * k;

        double m = fabs(z);
        double n = m * 0.0078125;
        double o = n + 1.0;
        double p = l / o;

        double q = java_min(p, 8.0);
        double r = java_max(q, 0.125);

        double s = x * y;
        double t = s * 0.00390625;
        double u = z * 0.001953125;
        double v = t + u;
        double w = fabs(v);

        double aa = w + 0.25;
        double ab = aa * 0.5;
        double ac = r + ab;

        double ad = java_max(ac, 0.25);
        double ae = java_min(ad, 12.0);
        double af = java_min(java_max(ae, 0.125), 10.0);

        double ag = af - 0.75;
        double ah = fabs(ag);
        double ai = ah + 0.125;

        double aj = fabs(y);
        double ak = aj * 0.03125;
        double al = ak + 1.0;

        output[index] = ai / al;
    }
    return 0;
}
