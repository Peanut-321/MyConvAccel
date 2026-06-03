/**
 * cycle_bench.c — Quick RISC-V Cycle Measurement
 * Tests at 8×8 scale, extrapolates to 32×32
 */
#include <stdio.h>
#include <stdint.h>

static inline unsigned long read_cycles(void) {
    unsigned long c;
    asm volatile ("rdcycle %0" : "=r" (c));
    return c;
}

static inline int16_t fixed_mul(int16_t a, int16_t b) {
    return (int16_t)(((int32_t)a * (int32_t)b) >> 8);
}

static void conv5x5_same(const int16_t *in, const int16_t *k, int16_t *out, int N) {
    for (int or = 0; or < N; or++)
        for (int oc = 0; oc < N; oc++) {
            int32_t sum = 0;
            for (int kr = 0; kr < 5; kr++)
                for (int kc = 0; kc < 5; kc++) {
                    int ir = or + kr - 2, ic = oc + kc - 2;
                    int16_t p = (ir>=0&&ir<N&&ic>=0&&ic<N) ? in[ir*N+ic] : 0;
                    sum += fixed_mul(p, k[kr*5+kc]);
                }
            if (sum>32767) sum=32767; else if (sum<-32768) sum=-32768;
            out[or*N+oc] = (int16_t)sum;
        }
}

int main(void) {
    static int16_t in1[8*8], k1[25], o1[8*8], v1[8*8];
    static int16_t in2[16*16], k2[25], o2[16*16], v2[16*16];

    /* Fill */
    for (int i=0;i<64;i++)  in1[i]=(i*17)&0xFF;
    for (int i=0;i<256;i++) in2[i]=(i*17)&0xFF;
    for (int i=0;i<25;i++)  { k1[i]=(i*7)&0xFF; k2[i]=k1[i]; }

    printf("=== RISC-V Conv Cycle Benchmark ===\n");

    /* 8×8 warmup + measure */
    conv5x5_same(in1, k1, v1, 8);
    unsigned long t0 = read_cycles();
    conv5x5_same(in1, k1, o1, 8);
    unsigned long t1 = read_cycles();
    unsigned long c8 = t1 - t0;
    printf("8x8:   %lu cycles  (macs=%d)\n", c8, 8*8*25);

    /* 16×16 */
    conv5x5_same(in2, k2, v2, 16);
    t0 = read_cycles();
    conv5x5_same(in2, k2, o2, 16);
    t1 = read_cycles();
    unsigned long c16 = t1 - t0;
    printf("16x16: %lu cycles  (macs=%d)\n", c16, 16*16*25);

    /* Extrapolate to 32×32 */
    unsigned long c32_est = c16 * 4;  /* 4x more pixels */
    printf("\n32x32 extrapolated: ~%lu cycles\n", c32_est);
    printf("HW cycles (theoretical): ~2500\n");
    printf("Ratio (32×32): ~%lu\n", c32_est / 2500);

    /* Also measure just the inner MAC loop cost */
    int16_t a=100, b=200;
    t0 = read_cycles();
    volatile int16_t r = fixed_mul(a, b);
    t1 = read_cycles();
    printf("\nSingle MAC: %lu cycles\n", t1 - t0);
    (void)r;

    return 0;
}
