/**
 * sw_conv_bench.c — RISC-V Software-Only 5×5 Convolution Benchmark
 *
 * Runs SAME 5×5 conv purely in C on the RISC-V core (no RoCC).
 * Uses rdcycle to measure CPU cycles for acceleration-ratio comparison.
 *
 * Expected: ~400,000–600,000 cycles for 32×32×5×5 = 25,600 MACs
 *
 * Usage: make sw_conv_bench.riscv && run on RISC-V
 */

#include <stdio.h>
#include <stdint.h>
#include <string.h>

/* ── Q8.8 fixed-point ── */
static inline int16_t fixed_mul(int16_t a, int16_t b) {
    return (int16_t)(((int32_t)a * (int32_t)b) >> 8);
}

static inline int16_t saturate(int32_t v) {
    if (v > 32767)       return 32767;
    else if (v < -32768) return -32768;
    else                 return (int16_t)v;
}

/* ── rdcycle ── */
static inline unsigned long read_cycles(void) {
    unsigned long c;
    asm volatile ("rdcycle %0" : "=r" (c));
    return c;
}

/* ── 5×5 SAME convolution, Q8.8 ── */
static void conv5x5_same(const int16_t *in, const int16_t *kernel,
                         int16_t *out, int N) {
    for (int or = 0; or < N; or++) {
        for (int oc = 0; oc < N; oc++) {
            int32_t sum = 0;
            for (int kr = 0; kr < 5; kr++) {
                for (int kc = 0; kc < 5; kc++) {
                    int ir = or + kr - 2;
                    int ic = oc + kc - 2;
                    int16_t pix = 0;
                    if (ir >= 0 && ir < N && ic >= 0 && ic < N)
                        pix = in[ir * N + ic];
                    sum += fixed_mul(pix, kernel[kr * 5 + kc]);
                }
            }
            out[or * N + oc] = saturate(sum);
        }
    }
}

/* ── Simple fill ── */
static void fill_pattern(int16_t *arr, int n, unsigned seed) {
    for (int i = 0; i < n; i++) {
        seed = seed * 1103515245 + 12345;
        arr[i] = (int16_t)(((seed >> 16) & 0x7F) - 64);  /* small values, avoid saturation */
    }
}

/* ── Verify two arrays match ── */
static int check_match(const int16_t *a, const int16_t *b, int n) {
    for (int i = 0; i < n; i++)
        if (a[i] != b[i]) return 0;
    return 1;
}

int main(void) {
    const int N  = 32;
    const int N2 = N * N;
    const int K  = 25;

    /* Use static storage for predictable RISC-V performance */
    static int16_t input[32 * 32]   __attribute__((aligned(8)));
    static int16_t kernel[25]       __attribute__((aligned(4)));
    static int16_t output[32 * 32]  __attribute__((aligned(8)));
    static int16_t verify[32 * 32]  __attribute__((aligned(8)));

    printf("╔══════════════════════════════════════════════════╗\n");
    printf("║   RISC-V Software-Only 5×5 Conv Benchmark      ║\n");
    printf("╚══════════════════════════════════════════════════╝\n\n");

    /* Fill with deterministic data */
    fill_pattern(input,  N2, 42);
    fill_pattern(kernel, K,  99);

    /* ─── Warmup (cache priming, also prints progress) ─── */
    printf("Warmup...");
    conv5x5_same(input, kernel, verify, N);
    printf("done. Running...\n");

    /* ─── Timed Run (single iteration, simulator is slow) ─── */
    unsigned long t0 = read_cycles();
    conv5x5_same(input, kernel, output, N);
    unsigned long t1 = read_cycles();
    unsigned long best_cycles = t1 - t0;

    /* ─── Verify correctness ─── */
    conv5x5_same(input, kernel, verify, N);
    int match = check_match(output, verify, N2);
    printf("  Correctness: %s\n", match ? "PASS" : "FAIL");

    /* ─── Results ─── */
    unsigned long macs = (unsigned long)N * N * 5 * 5;
    printf("\n");
    printf("  SW_CYCLES=%lu\n", best_cycles);
    printf("  MACs=%lu CyclesPerMAC=%lu\n", macs, best_cycles / macs);
    printf("  Expected HW_CYCLES ~2500\n");
    printf("  Expected RATIO ~%lu\n", best_cycles / 2500);

    return match ? 0 : 1;
}
