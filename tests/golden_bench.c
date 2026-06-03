/**
 * golden_bench.c — Standalone C Golden Model + Performance Benchmark
 *
 * Implements the SAME 5×5 convolution in pure C with Q8.8 fixed-point.
 * Runs natively (not RISC-V) for baseline performance comparison.
 *
 * Usage: gcc -O2 golden_bench.c -o golden_bench && ./golden_bench
 */

#define _POSIX_C_SOURCE 199309L
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ── Q8.8 fixed-point ── */
#define FLOAT_TO_FIXED(x)  ((int16_t)((x) * 256.0f))
#define FIXED_TO_FLOAT(x)  ((float)(x) / 256.0f)

static inline int16_t fixed_mul(int16_t a, int16_t b) {
    return (int16_t)(((int32_t)a * (int32_t)b) >> 8);
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
            if      (sum >  32767) sum =  32767;
            else if (sum < -32768) sum = -32768;
            out[or * N + oc] = (int16_t)sum;
        }
    }
}

/* ── Deterministic "random" fill ── */
static void fill_random(int16_t *arr, int n, unsigned seed) {
    for (int i = 0; i < n; i++) {
        seed = seed * 1103515245 + 12345;
        arr[i] = (int16_t)(((seed >> 16) & 0xFF) - 128);
    }
}

/* ── Check two arrays match ── */
static int check_match(const int16_t *a, const int16_t *b, int n,
                       const char *label) {
    int mismatches = 0;
    for (int i = 0; i < n; i++) {
        if (a[i] != b[i]) {
            if (mismatches < 10)
                printf("  MISMATCH idx=%d a=%d b=%d\n", i, a[i], b[i]);
            mismatches++;
        }
    }
    if (mismatches == 0)
        printf("[%s] ALL %d elements MATCH\n", label, n);
    else
        printf("[%s] %d MISMATCHES\n", label, mismatches);
    return mismatches == 0;
}

int main(void) {
    const int N = 32;
    const int N2 = N * N;
    const int K = 25;

    int16_t *input  = (int16_t *)aligned_alloc(8, N2 * sizeof(int16_t));
    int16_t *kernel = (int16_t *)aligned_alloc(2, K  * sizeof(int16_t));
    int16_t *out1   = (int16_t *)aligned_alloc(8, N2 * sizeof(int16_t));
    int16_t *out2   = (int16_t *)aligned_alloc(8, N2 * sizeof(int16_t));

    printf("╔══════════════════════════════════════════════════╗\n");
    printf("║   CNN 5×5 Conv — C Golden Model Benchmark       ║\n");
    printf("╚══════════════════════════════════════════════════╝\n\n");

    /* ─── Test 1: Center identity ─── */
    printf("--- Test 1: Center Identity ---\n");
    memset(input, 0, N2 * sizeof(int16_t));
    memset(kernel, 0, K * sizeof(int16_t));
    memset(out1, 0, N2 * sizeof(int16_t));
    input[16 * N + 16]   = 256;    /* 1.0 */
    kernel[2 * 5 + 2]    = 256;    /* center tap = 1.0 */
    conv5x5_same(input, kernel, out1, N);
    int ok = (out1[16 * N + 16] == 256);
    for (int i = 0; i < N2; i++)
        if (i != 16 * N + 16 && out1[i] != 0) ok = 0;
    printf("  %s\n", ok ? "PASS" : "FAIL");

    /* ─── Test 2: All 25 single-tap positions ─── */
    printf("\n--- Test 2: All 25 Single-Tap Expected Positions ---\n");
    printf("  (input[16][16]=256, each kernel tap=256)\n");
    memset(input, 0, N2 * sizeof(int16_t));
    input[16 * N + 16] = 256;
    for (int kr = 0; kr < 5; kr++) {
        for (int kc = 0; kc < 5; kc++) {
            memset(kernel, 0, K * sizeof(int16_t));
            memset(out1, 0, N2 * sizeof(int16_t));
            kernel[kr * 5 + kc] = 256;
            conv5x5_same(input, kernel, out1, N);
            int exp_row = 18 - kr;
            int exp_col = 18 - kc;
            int exp_idx = exp_row * N + exp_col;
            int found = 0, correct = 0;
            for (int i = 0; i < N2; i++) {
                if (out1[i] != 0) {
                    found++;
                    if (i == exp_idx && out1[i] == 256) correct = 1;
                }
            }
            printf("  k[%d][%d]: exp=(r%d,c%d,idx%4d) ", kr, kc, exp_row, exp_col, exp_idx);
            if (found == 1 && correct) printf("OK\n");
            else printf("found=%d correct=%d\n", found, correct);
        }
    }

    /* ─── Test 3: Weighted row-0 ─── */
    printf("\n--- Test 3: Weighted Row-0 Kernel (Golden) ---\n");
    memset(input, 0, N2 * sizeof(int16_t));
    memset(kernel, 0, K * sizeof(int16_t));
    memset(out1, 0, N2 * sizeof(int16_t));
    input[16 * N + 16] = 256;
    kernel[0] = 256;    /* 256*1 */
    kernel[1] = 512;    /* 256*2 */
    kernel[2] = 768;    /* 256*3 */
    kernel[3] = 1024;   /* 256*4 */
    kernel[4] = 1280;   /* 256*5 */
    conv5x5_same(input, kernel, out1, N);
    int exp_idx[5] = {594, 593, 592, 591, 590};
    int16_t exp_val[5] = {256, 512, 768, 1024, 1280};
    ok = 1;
    for (int k = 0; k < 5; k++) {
        if (out1[exp_idx[k]] != exp_val[k]) {
            printf("  WRONG k[%d]: idx=%d exp=%d got=%d\n",
                   k, exp_idx[k], exp_val[k], out1[exp_idx[k]]);
            ok = 0;
        }
    }
    /* Check no extras */
    int extra = 0;
    for (int i = 0; i < N2; i++) {
        if (out1[i] != 0) {
            int is_exp = 0;
            for (int k = 0; k < 5; k++) if (i == exp_idx[k]) is_exp = 1;
            if (!is_exp) { printf("  EXTRA idx=%d val=%d\n", i, out1[i]); extra++; }
        }
    }
    printf("  %s (extra=%d)\n", ok && extra == 0 ? "PASS" : "FAIL", extra);

    /* ─── Test 4: Edge cases ─── */
    printf("\n--- Test 4: Edge / Corner Cases ---\n");
    const char *names[] = {"TL","TR","BL","BR","TM","BM","LM","RM"};
    int rows[] = {0,0,31,31,0,31,16,16};
    int cols[] = {0,31,0,31,16,16,0,31};
    for (int t = 0; t < 8; t++) {
        memset(input, 0, N2 * sizeof(int16_t));
        memset(kernel, 0, K * sizeof(int16_t));
        memset(out1, 0, N2 * sizeof(int16_t));
        input[rows[t] * N + cols[t]] = 256;
        kernel[2 * 5 + 2] = 256;
        conv5x5_same(input, kernel, out1, N);
        int exp_i = rows[t] * N + cols[t];
        ok = (out1[exp_i] == 256);
        for (int i = 0; i < N2; i++)
            if (i != exp_i && out1[i] != 0) ok = 0;
        printf("  %2s (r%d,c%d): %s\n", names[t], rows[t], cols[t], ok ? "PASS" : "FAIL");
    }

    /* ─── Performance Benchmark ─── */
    printf("\n╔══════════════════════════════════════════════════╗\n");
    printf("║   Performance Benchmark                          ║\n");
    printf("╚══════════════════════════════════════════════════╝\n\n");

    fill_random(input, N2, 42);
    fill_random(kernel, K, 99);

    const int WARMUP = 3;
    const int ITERS  = 50;

    /* Warmup */
    for (int i = 0; i < WARMUP; i++)
        conv5x5_same(input, kernel, out1, N);

    /* Timed run */
    uint64_t total_ns = 0;
    for (int iter = 0; iter < ITERS; iter++) {
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        conv5x5_same(input, kernel, out1, N);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        total_ns += (t1.tv_sec - t0.tv_sec) * 1000000000ULL +
                    (t1.tv_nsec - t0.tv_nsec);
    }
    double avg_us = total_ns / (1000.0 * ITERS);

    /* Verify correctness */
    conv5x5_same(input, kernel, out2, N);
    int match = check_match(out1, out2, N2, "bench-verify");

    /* Operation count */
    long ops_per_conv = (long)N * N * 5 * 5;         /* 32*32*25 = 25600 MACs */
    long total_ops = ops_per_conv * ITERS;

    printf("\n");
    printf("  Image size:        %d × %d\n", N, N);
    printf("  Kernel size:       5 × 5\n");
    printf("  MACs per conv:     %ld\n", ops_per_conv);
    printf("  Iterations:        %d\n", ITERS);
    printf("  Avg time per conv: %.2f µs\n", avg_us);
    printf("  Total MACs:        %ld\n", total_ops);
    printf("  Throughput:        %.2f MACs/s (pure C)\n",
           ops_per_conv / (avg_us / 1e6));
    printf("\n");
    printf("  Notes for hardware comparison:\n");
    printf("  - Hardware runs at ~?? MHz (check your config)\n");
    printf("  - Hardware pipeline: 1 result per cycle after warmup\n");
    printf("  - Ideal hardware: 1024 cycles for 32×32 output\n");
    printf("  - Software: %.0f cycles @ 1 GHz equivalent\n", avg_us * 1000);

    free(input); free(kernel); free(out1); free(out2);
    return match ? 0 : 1;
}
