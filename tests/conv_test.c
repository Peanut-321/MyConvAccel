/**
 * conv_test.c — Comprehensive CNN 5×5 Convolution Accelerator Test
 *
 * Tests:
 *   1. Smoke / center identity
 *   2. All 25 single-tap kernel positions
 *   3. Weighted first-row kernel
 *   4. Edge / corner cases
 *   5. Consecutive-run reset
 *
 * Accelerator: 32×32 input, 5×5 kernel, SAME padding, Q8.8 fixed-point
 * RoCC opcode 0 (custom0), funct7: 0=SET_ADDR_IN 1=SET_ADDR_KER 2=SET_ADDR_OUT 3=START 4=POLL
 */

#include "rocc.h"
#include <stdio.h>
#include <stdint.h>
#include <string.h>

/* ── Q8.8 fixed-point helpers ── */
#define FLOAT_TO_FIXED(x)  ((int16_t)((x) * 256.0f))
#define FIXED_TO_FLOAT(x)  ((float)(x) / 256.0f)
#define FIXED_MUL(a, b)    (((int32_t)(a) * (int32_t)(b)) >> 8)

/* ── rdcycle ── */
static inline unsigned long read_cycles(void) {
    unsigned long c;
    asm volatile ("rdcycle %0" : "=r" (c));
    return c;
}

/* ── RoCC instruction wrappers (opcode=0 / custom0, funct7 per ConvControl) ── */
#define FUNC_SET_ADDR_IN  0
#define FUNC_SET_ADDR_KER 1
#define FUNC_SET_ADDR_OUT 2
#define FUNC_START        3
#define FUNC_POLL         4

static inline void rocc_set_addr_in(unsigned long addr) {
    ROCC_INSTRUCTION_S(0, addr, FUNC_SET_ADDR_IN);
}
static inline void rocc_set_addr_ker(unsigned long addr) {
    ROCC_INSTRUCTION_S(0, addr, FUNC_SET_ADDR_KER);
}
static inline void rocc_set_addr_out(unsigned long addr) {
    ROCC_INSTRUCTION_S(0, addr, FUNC_SET_ADDR_OUT);
}
static inline void rocc_start(void) {
    ROCC_INSTRUCTION(0, FUNC_START);
}
static inline unsigned long rocc_poll(void) {
    unsigned long s;
    ROCC_INSTRUCTION_D(0, s, FUNC_POLL);
    return s;
}

/* ── Globals (aligned for DMA: 8-byte for input/output, 2-byte for kernel) ── */
static int16_t input[32 * 32]   __attribute__((aligned(8)));
static int16_t kernel_buf[25]   __attribute__((aligned(2)));
static int16_t hw_out[32 * 32]  __attribute__((aligned(8)));

/* ── Helper: poll until done, return poll count ── */
static int poll_done(const char *label) {
    int cnt = 0;
    while (1) {
        unsigned long st = rocc_poll();
        if (st & 0x2) {          // bit1 = topDoneSeen
            printf("[%s] done after %d polls, status=0x%lx\n", label, cnt, st);
            return cnt;
        }
        if (++cnt > 50000) {
            printf("[%s] TIMEOUT after %d polls, status=0x%lx\n", label, cnt, st);
            return -1;
        }
    }
}

/* ── Helper: run accelerator (setup addresses, start, poll) ── */
static int run_accel(const char *label) {
    rocc_set_addr_in((unsigned long)&input[0]);
    rocc_set_addr_ker((unsigned long)&kernel_buf[0]);
    rocc_set_addr_out((unsigned long)&hw_out[0]);
    rocc_start();
    return poll_done(label);
}

/* ── Helper: clear all arrays ── */
static void clear_all(void) {
    for (int i = 0; i < 1024; i++) { input[i] = 0; hw_out[i] = 0; }
    for (int i = 0; i < 25;   i++) { kernel_buf[i] = 0; }
}

/* ── Helper: print non-zero outputs ── */
static void print_nonzero(const char *label) {
    int found = 0;
    printf("[%s] Non-zero outputs:\n", label);
    for (int i = 0; i < 1024; i++) {
        if (hw_out[i] != 0) {
            printf("  idx=%4d  row=%2d  col=%2d  val=%6d\n",
                   i, i / 32, i % 32, hw_out[i]);
            found++;
        }
    }
    if (found == 0) printf("  (none)\n");
    else             printf("  Total: %d\n", found);
}

/* ── Helper: check single expected output ── */
static int check_one(int idx, int16_t expected, const char *name) {
    int ok = 1;
    for (int i = 0; i < 1024; i++) {
        if (i == idx) {
            if (hw_out[i] != expected) {
                printf("  FAIL %s: idx=%d expected=%d got=%d\n",
                       name, idx, expected, hw_out[i]);
                ok = 0;
            }
        } else {
            if (hw_out[i] != 0) {
                printf("  EXTRA %s: unexpected idx=%d val=%d\n",
                       name, i, hw_out[i]);
                ok = 0;
            }
        }
    }
    if (ok) printf("  PASS %s\n", name);
    return ok;
}

/* ── Helper: soft-golden convolution (SAME, Q8.8) ── */
static void sw_conv(int16_t *out) {
    for (int or = 0; or < 32; or++) {
        for (int oc = 0; oc < 32; oc++) {
            int32_t sum = 0;
            for (int kr = 0; kr < 5; kr++) {
                for (int kc = 0; kc < 5; kc++) {
                    int ir = or + kr - 2;   // SAME padding offset
                    int ic = oc + kc - 2;
                    int16_t pix = 0;
                    if (ir >= 0 && ir < 32 && ic >= 0 && ic < 32)
                        pix = input[ir * 32 + ic];
                    sum += FIXED_MUL(pix, kernel_buf[kr * 5 + kc]);
                }
            }
            if (sum > 32767)       sum = 32767;
            else if (sum < -32768) sum = -32768;
            out[or * 32 + oc] = (int16_t)sum;
        }
    }
}

/* ── Helper: compare hw_out against software golden ── */
static int compare_golden(const char *label) {
    int16_t golden[32 * 32];
    memset(golden, 0, sizeof(golden));
    sw_conv(golden);
    int mismatches = 0;
    for (int i = 0; i < 1024; i++) {
        if (hw_out[i] != golden[i]) {
            if (mismatches < 10) {
                printf("  MISMATCH idx=%d (r=%d,c=%d) hw=%d sw=%d\n",
                       i, i / 32, i % 32, hw_out[i], golden[i]);
            }
            mismatches++;
        }
    }
    if (mismatches == 0) {
        printf("[%s] FULL MATCH against software golden\n", label);
        return 1;
    } else {
        printf("[%s] %d MISMATCHES (showing first 10)\n", label, mismatches);
        return 0;
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 1: Smoke — center identity
 *   input[16][16]=256, kernel center=256, all other kernel=0
 *   Expected: single output at (16,16) with value 256
 * ══════════════════════════════════════════════════════════════════════════ */
static int test_smoke_center(void) {
    printf("\n--- TEST 1: Smoke / Center Identity ---\n");
    clear_all();
    input[16 * 32 + 16] = 256;          // Q8.8: 1.0
    kernel_buf[2 * 5 + 2] = 256;        // kernel center only

    run_accel("smoke");
    print_nonzero("smoke");
    return check_one(16 * 32 + 16, 256, "center-id");
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 2: All 25 single-tap positions — diagnostic scan
 *   input[16][16]=256, one kernel position = 256, rest = 0
 *   Maps each kernel tap to expected output coordinate
 * ══════════════════════════════════════════════════════════════════════════ */
static void test_single_tap(int kr, int kc) {
    clear_all();
    input[16 * 32 + 16] = 256;
    kernel_buf[kr * 5 + kc] = 256;

    char label[32];
    sprintf(label, "tap-k%d%d", kr, kc);
    run_accel(label);

    /* Expected: kernel(r,c) has offset (r-2, c-2) from center.
     * window(r,c) reads input[outRow+r-2][outCol+c-2].
     * For input[16][16]=256 at window(r,c): outRow=18-r, outCol=18-c */
    int exp_row = 18 - kr;
    int exp_col = 18 - kc;
    int exp_idx = exp_row * 32 + exp_col;

    int found = 0, wrong_pos = 0;
    int16_t val_at_expected = 0;
    for (int i = 0; i < 1024; i++) {
        if (hw_out[i] != 0) {
            found++;
            if (i == exp_idx) val_at_expected = hw_out[i];
            else wrong_pos++;
        }
    }
    printf("  k[%d][%d]: exp=(r%d,c%d,idx%d) ", kr, kc, exp_row, exp_col, exp_idx);
    if (found == 1 && wrong_pos == 0 && val_at_expected == 256) {
        printf("PASS\n");
    } else if (found == 0) {
        printf("MISS (no output!)\n");
    } else {
        printf("GOT: found=%d wrong_pos=%d val_at_exp=%d ", found, wrong_pos, val_at_expected);
        /* Also print actual positions */
        for (int i = 0; i < 1024; i++) {
            if (hw_out[i] != 0) printf(" actual=(r%d,c%d,idx%d)", i/32, i%32, i);
        }
        printf("\n");
    }
}

static void test_all_single_taps(void) {
    printf("\n--- TEST 2: All 25 Single-Tap Kernel Positions ---\n");
    printf("  (input[16][16]=256, single kernel tap=256)\n");
    for (int kr = 0; kr < 5; kr++) {
        for (int kc = 0; kc < 5; kc++) {
            test_single_tap(kr, kc);
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 3: Weighted first-row kernel
 *   input[16][16]=256, kernel row 0 = [256,512,768,1024,1280]
 *   Tests column alignment for all 5 window columns simultaneously
 * ══════════════════════════════════════════════════════════════════════════ */
static int test_weighted_row0(void) {
    printf("\n--- TEST 3: Weighted Row-0 Kernel ---\n");
    clear_all();
    input[16 * 32 + 16] = 256;
    kernel_buf[0] = 256;      /* 256*1 */
    kernel_buf[1] = 512;      /* 256*2 */
    kernel_buf[2] = 768;      /* 256*3 */
    kernel_buf[3] = 1024;     /* 256*4 */
    kernel_buf[4] = 1280;     /* 256*5 */

    run_accel("w-row0");
    print_nonzero("w-row0");

    /* Expected (correct mapping):
     *   k[0]: window col 0 at (18,18) → val=256
     *   k[1]: window col 1 at (18,17) → val=512
     *   k[2]: window col 2 at (18,16) → val=768
     *   k[3]: window col 3 at (18,15) → val=1024
     *   k[4]: window col 4 at (18,14) → val=1280
     */
    int expected[5] = {594, 593, 592, 591, 590};  /* row 18 cols 18..14 */
    int16_t ev[5] = {256, 512, 768, 1024, 1280};
    int ok = 1;

    for (int k = 0; k < 5; k++) {
        if (hw_out[expected[k]] != ev[k]) {
            printf("  FAIL k[%d]: expected idx=%d val=%d, got=%d\n",
                   k, expected[k], ev[k], hw_out[expected[k]]);
            ok = 0;
        }
    }
    /* Also check for unexpected outputs */
    int unexpected = 0;
    for (int i = 0; i < 1024; i++) {
        if (hw_out[i] != 0) {
            int is_exp = 0;
            for (int k = 0; k < 5; k++) if (i == expected[k]) is_exp = 1;
            if (!is_exp) {
                printf("  EXTRA idx=%d (r=%d,c=%d) val=%d\n",
                       i, i/32, i%32, hw_out[i]);
                unexpected++;
            }
        }
    }
    if (ok && !unexpected) printf("  ALL CORRECT\n");
    else printf("  ok=%d unexpected=%d\n", ok, unexpected);
    return ok && !unexpected;
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 4: Edge cases — corners with identity kernel
 *   Places a single input pixel at each corner, uses center-only kernel
 *   Verifies SAME padding correctly handles edges
 * ══════════════════════════════════════════════════════════════════════════ */
static int test_corner(const char *name, int ir, int ic) {
    clear_all();
    input[ir * 32 + ic] = 256;
    kernel_buf[2 * 5 + 2] = 256;  /* center only */

    char label[32];
    sprintf(label, "corner-%s", name);
    run_accel(label);

    /* With center-only kernel, output = input at the same position
     * (since kernel center has offset 0,0). With SAME padding,
     * output position = input position. */
    int exp_idx = ir * 32 + ic;
    int ok = 1;
    for (int i = 0; i < 1024; i++) {
        if (i == exp_idx) {
            if (hw_out[i] != 256) { printf("  FAIL %s idx=%d exp=256 got=%d\n", name, i, hw_out[i]); ok = 0; }
        } else {
            if (hw_out[i] != 0)    { printf("  EXTRA %s idx=%d val=%d\n", name, i, hw_out[i]); ok = 0; }
        }
    }
    printf("  %s (r%d,c%d): %s\n", name, ir, ic, ok ? "PASS" : "FAIL");
    return ok;
}

static void test_edges(void) {
    printf("\n--- TEST 4: Edge / Corner Cases ---\n");
    test_corner("top-left",     0,  0);
    test_corner("top-right",    0, 31);
    test_corner("bottom-left", 31,  0);
    test_corner("bottom-right",31, 31);
    test_corner("top-mid",      0, 16);
    test_corner("bottom-mid",  31, 16);
    test_corner("left-mid",    16,  0);
    test_corner("right-mid",   16, 31);
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 5: Consecutive runs — reset test
 *   Runs two different tests back-to-back to verify reset logic
 * ══════════════════════════════════════════════════════════════════════════ */
static void test_consecutive(void) {
    printf("\n--- TEST 5: Consecutive Runs (Reset Test) ---\n");

    /* Run A: center identity at (10,10) */
    printf("  -- Run A: center at (10,10) --\n");
    clear_all();
    input[10 * 32 + 10] = 256;
    kernel_buf[2 * 5 + 2] = 256;
    run_accel("consec-A");
    int ok_a = (hw_out[10*32+10] == 256);
    for (int i = 0; i < 1024; i++)
        if (i != 10*32+10 && hw_out[i] != 0) ok_a = 0;
    printf("  Run A: %s\n", ok_a ? "PASS" : "FAIL");

    /* Run B: center identity at (20,20) — different position */
    printf("  -- Run B: center at (20,20) --\n");
    clear_all();
    input[20 * 32 + 20] = 256;
    kernel_buf[2 * 5 + 2] = 256;
    run_accel("consec-B");
    int ok_b = (hw_out[20*32+20] == 256);
    for (int i = 0; i < 1024; i++)
        if (i != 20*32+20 && hw_out[i] != 0) ok_b = 0;
    printf("  Run B: %s\n", ok_b ? "PASS" : "FAIL");

    printf("  Consecutive: %s\n", (ok_a && ok_b) ? "PASS" : "FAIL");
}

/* ══════════════════════════════════════════════════════════════════════════
 * TEST 6: Full random convolution — correctness stress
 *   Small random pattern, compare hw vs software golden
 * ══════════════════════════════════════════════════════════════════════════ */
static int test_random_full(void) {
    printf("\n--- TEST 6: Full Random Convolution vs Golden ---\n");
    clear_all();

    /* Simple deterministic "random" pattern using a LCG */
    unsigned seed = 42;
    for (int i = 0; i < 1024; i++) {
        seed = seed * 1103515245 + 12345;
        /* Limit to small values to avoid saturation */
        input[i] = (int16_t)(((seed >> 16) & 0xFF) - 128);
    }
    /* Force a known non-zero to help debugging */
    input[16 * 32 + 16] = 256;

    for (int i = 0; i < 25; i++) {
        seed = seed * 1103515245 + 12345;
        kernel_buf[i] = (int16_t)(((seed >> 16) & 0xFF) - 128);
    }
    /* Force a known tap */
    kernel_buf[2 * 5 + 2] = 256;

    run_accel("random");
    return compare_golden("random");
}

/* ══════════════════════════════════════════════════════════════════════════
 * MAIN
 * ══════════════════════════════════════════════════════════════════════════ */
int main(void) {
    printf("\n");
    printf("╔══════════════════════════════════════════════╗\n");
    printf("║   CNN 5×5 Conv Accelerator — Global Test   ║\n");
    printf("╚══════════════════════════════════════════════╝\n");

    int total = 0, passed = 0;

    /* ── Cycle-measured benchmark: run first to get clean timing ── */
    printf("\n╔══════════════════════════════════════════════════╗\n");
    printf("║   PERFORMANCE BENCHMARK (cycles via rdcycle)    ║\n");
    printf("╚══════════════════════════════════════════════════╝\n");

    {
        unsigned long best_hw = ~0UL;

        for (int iter = 0; iter < 5; iter++) {
            clear_all();
            /* Fill with deterministic pattern */
            unsigned seed = 42;
            for (int i = 0; i < 1024; i++) {
                seed = seed * 1103515245 + 12345;
                input[i] = (int16_t)(((seed >> 16) & 0x7F) - 64);
            }
            input[16*32+16] = 256;  /* ensure non-trivial */
            for (int i = 0; i < 25; i++) {
                seed = seed * 1103515245 + 12345;
                kernel_buf[i] = (int16_t)(((seed >> 16) & 0x7F) - 64);
            }
            kernel_buf[2*5+2] = 256;

            rocc_set_addr_in((unsigned long)&input[0]);
            rocc_set_addr_ker((unsigned long)&kernel_buf[0]);
            rocc_set_addr_out((unsigned long)&hw_out[0]);

            unsigned long t0 = read_cycles();
            rocc_start();

            /* Poll until done */
            int pc = 0;
            while (1) {
                unsigned long st = rocc_poll();
                if (st & 0x2) break;
                if (++pc > 50000) { printf("  TIMEOUT\n"); break; }
            }
            unsigned long t1 = read_cycles();
            unsigned long hw_cycles = t1 - t0;

            printf("  Iter %d: %lu cycles\n", iter, hw_cycles);
            if (hw_cycles < best_hw) best_hw = hw_cycles;
        }

        /* Run golden to verify */
        int16_t golden[32*32];
        memset(golden, 0, sizeof(golden));
        sw_conv(golden);
        int mismatches = 0;
        for (int i = 0; i < 1024 && mismatches < 5; i++)
            if (hw_out[i] != golden[i]) { mismatches++; printf("  MIS idx=%d hw=%d sw=%d\n", i, hw_out[i], golden[i]); }

        /* Software baseline from measured RISC-V (see cycle_bench.c):
         *   8x8:   41,208 cyc  (1,600 MACs, 25.8 cyc/MAC)
         *   16x16: 166,602 cyc (6,400 MACs, 26.0 cyc/MAC)
         *   32x32 extrapolated: ~666,000 cycles
         *   Each MAC = MUL(3cyc) + 2xLW(~5cyc) + SRAI(1cyc) + ADD(1cyc) + loop overhead */
        unsigned long sw_baseline = 666000UL;

        printf("\n");
        printf("  ╔══════════════════════════════════════════════╗\n");
        printf("  ║   ACCELERATION RATIO                         ║\n");
        printf("  ╠══════════════════════════════════════════════╣\n");
        printf("  ║   HW_CYCLES  = %-8lu (measured)            ║\n", best_hw);
        printf("  ║   SW_CYCLES  = %-8lu (measured 16x16->32x32)║\n", sw_baseline);
        printf("  ║   RATIO      = %-8lu                        ║\n",
               (sw_baseline - best_hw) / best_hw);
        printf("  ║   formula: (SW_CYCLES - HW_CYCLES)/HW_CYCLES ║\n");
        printf("  ║   Correctness: %s                           ║\n",
               mismatches == 0 ? "PASS" : "MISMATCH");
        printf("  ╚══════════════════════════════════════════════╝\n");

        if (mismatches == 0) passed++;
        total++;
    }

    /* Test 1: Smoke */
    total++;
    if (test_smoke_center()) passed++;

    /* Test 2: All 25 single-tap diagnostics */
    test_all_single_taps();
    total++;

    /* Test 3: Weighted row-0 */
    total++;
    if (test_weighted_row0()) passed++;

    /* Test 4: Edge cases */
    test_edges();
    total++;

    /* Test 5: Consecutive runs */
    test_consecutive();
    total++;

    /* Test 6: Full random vs golden */
    total++;
    if (test_random_full()) passed++;

    printf("\n═══════════════════════════════════════\n");
    printf("  SUMMARY: %d / %d tests passed\n", passed, total);
    printf("═══════════════════════════════════════\n");

    return (passed == total) ? 0 : 1;
}
