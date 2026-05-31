#include <stdint.h>
#include <stdio.h>
#include "rocc.h"

#define INPUT_W 32
#define INPUT_H 32
#define INPUT_SIZE 1024

#define KERNEL_W 5
#define KERNEL_H 5
#define KERNEL_SIZE 25

// Hardware currently loads 64 bytes for kernel = 32 x int16_t
#define KERNEL_HW_SIZE 32

// Top currently stores 2176 bytes = 1088 x int16_t
#define HW_OUT_SIZE 1088

// Software reference output region: 32 x 32 = 1024
#define OUT_SIZE 1024

#define MAX_POLL 100000

// ------------------------------------------------------------
// RoCC instruction wrappers
// funct7 mapping:
// 0: set input address
// 1: set kernel address
// 2: set output address
// 3: start accelerator
// 4: poll status
// ------------------------------------------------------------

static inline void set_addr_in(uint64_t addr) {
    ROCC_INSTRUCTION_SS(0, addr, 0, 0);
}

static inline void set_addr_ker(uint64_t addr) {
    ROCC_INSTRUCTION_SS(0, addr, 0, 1);
}

static inline void set_addr_out(uint64_t addr) {
    ROCC_INSTRUCTION_SS(0, addr, 0, 2);
}

static inline void start_accel(void) {
    ROCC_INSTRUCTION_SS(0, 0, 0, 3);
}

static inline uint64_t poll_status(void) {
    uint64_t status;
    ROCC_INSTRUCTION_DSS(0, status, 0, 0, 4);
    return status;
}

static inline uint64_t rdcycle(void) {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r"(cycles));
    return cycles;
}

static inline void fence_rw(void) {
    asm volatile ("fence rw, rw" ::: "memory");
}

// ------------------------------------------------------------
// Data buffers, 8-byte aligned for DMA.
// ------------------------------------------------------------

static int16_t input[INPUT_SIZE]      __attribute__((aligned(8)));
static int16_t kernel[KERNEL_HW_SIZE] __attribute__((aligned(8)));
static int16_t sw_out[OUT_SIZE]       __attribute__((aligned(8)));
static int16_t hw_out[HW_OUT_SIZE]    __attribute__((aligned(8)));

// ------------------------------------------------------------
// Saturation helper
// ------------------------------------------------------------

static int16_t sat16(int32_t x) {
    if (x > 32767) return 32767;
    if (x < -32768) return -32768;
    return (int16_t)x;
}

// ------------------------------------------------------------
// Software same-padding 5x5 convolution, Q8.8 version.
// Input and kernel are Q8.8 values.
// MAC result shifts right by 8.
// ------------------------------------------------------------

static void software_conv_5x5_same_q88(void) {
    for (int r = 0; r < INPUT_H; r++) {
        for (int c = 0; c < INPUT_W; c++) {
            int32_t acc = 0;

            for (int kr = 0; kr < KERNEL_H; kr++) {
                for (int kc = 0; kc < KERNEL_W; kc++) {
                    int ir = r + kr - 2;
                    int ic = c + kc - 2;

                    if (ir >= 0 && ir < INPUT_H && ic >= 0 && ic < INPUT_W) {
                        int16_t in_val = input[ir * INPUT_W + ic];
                        int16_t ker_val = kernel[kr * KERNEL_W + kc];
                        acc += (int32_t)in_val * (int32_t)ker_val;
                    }
                }
            }

            // Q8.8: after multiply Q8.8 * Q8.8 = Q16.16, shift back by 8
            sw_out[r * INPUT_W + c] = sat16(acc >> 8);
        }
    }
}

// ------------------------------------------------------------
// Pinpoint test data:
// Everything is zero except one input point and one kernel point.
// ------------------------------------------------------------

static void init_pinpoint_data(void) {
    for (int i = 0; i < INPUT_SIZE; i++) {
        input[i] = 0;
    }

    for (int i = 0; i < KERNEL_HW_SIZE; i++) {
        kernel[i] = 0;
    }

    for (int i = 0; i < OUT_SIZE; i++) {
        sw_out[i] = 0;
    }

    for (int i = 0; i < HW_OUT_SIZE; i++) {
        hw_out[i] = 0;
    }

    /*
     * Single input point.
     * Q8.8 value 1.0 = 256.
     * Logical position: row=16, col=16.
     * Flat index = 16 * 32 + 16 = 528.
     */
    input[16 * 32 + 16] = 256;

    /*
     * Identity kernel center.
     * Q8.8 value 1.0 = 256.
     * 5x5 center index = 2 * 5 + 2 = 12.
     */
    kernel[12] = 256;
}

// ------------------------------------------------------------
// Print nonzero hardware output.
// This tells us where the hardware output stream places the point.
// ------------------------------------------------------------

static void print_nonzero_hw_out(void) {
    volatile int16_t *hw = (volatile int16_t *)hw_out;
    int count = 0;

    printf("Nonzero hw_out values:\n");

    for (int i = 0; i < HW_OUT_SIZE; i++) {
        int16_t v = hw[i];

        if (v != 0) {
            printf("hw_out[%d] = %d\n", i, v);
            count++;
        }
    }

    printf("Nonzero hw_out count = %d\n", count);
}

// ------------------------------------------------------------
// Print nonzero software output.
// For ideal same-padding identity, only sw_out[528] should be 256.
// ------------------------------------------------------------

static void print_nonzero_sw_out(void) {
    int count = 0;

    printf("Nonzero sw_out values:\n");

    for (int i = 0; i < OUT_SIZE; i++) {
        if (sw_out[i] != 0) {
            printf("sw_out[%d] = %d\n", i, sw_out[i]);
            count++;
        }
    }

    printf("Nonzero sw_out count = %d\n", count);
}

// ------------------------------------------------------------
// Compare with offset scan.
// This is less important than nonzero print, but useful if output is shifted.
// ------------------------------------------------------------

static int compare_outputs_with_offset_scan(void) {
    volatile int16_t *hw = (volatile int16_t *)hw_out;

    int best_offset = 0;
    int best_errors = OUT_SIZE + 1;

    for (int offset = 0; offset <= (HW_OUT_SIZE - OUT_SIZE); offset++) {
        int errors = 0;

        for (int i = 0; i < OUT_SIZE; i++) {
            int16_t h = hw[offset + i];
            int16_t s = sw_out[i];

            if (h != s) {
                errors++;
            }
        }

        if (errors < best_errors) {
            best_errors = errors;
            best_offset = offset;
        }
    }

    printf("Best output offset = %d\n", best_offset);
    printf("Best offset errors = %d\n", best_errors);

    return best_errors;
}

int main(void) {
    uint64_t sw_start, sw_end;
    uint64_t acc_start, acc_end;
    uint64_t sw_cycles, acc_cycles;
    uint64_t status = 0;
    int poll_count = 0;

    init_pinpoint_data();

    printf("input addr  = 0x%lx\n", (uint64_t)(uintptr_t)input);
    printf("kernel addr = 0x%lx\n", (uint64_t)(uintptr_t)kernel);
    printf("hw_out addr = 0x%lx\n", (uint64_t)(uintptr_t)hw_out);

    printf("Pinpoint input index = %d\n", 16 * 32 + 16);
    printf("Pinpoint input value = %d\n", input[16 * 32 + 16]);
    printf("Kernel center index = 12\n");
    printf("Kernel center value = %d\n", kernel[12]);

    // --------------------------------------------------------
    // Software reference
    // --------------------------------------------------------
    sw_start = rdcycle();
    software_conv_5x5_same_q88();
    sw_end = rdcycle();
    sw_cycles = sw_end - sw_start;

    // --------------------------------------------------------
    // Accelerator run
    // --------------------------------------------------------
    set_addr_in((uint64_t)(uintptr_t)input);
    set_addr_ker((uint64_t)(uintptr_t)kernel);
    set_addr_out((uint64_t)(uintptr_t)hw_out);

    fence_rw();

    acc_start = rdcycle();

    start_accel();

    for (poll_count = 0; poll_count < MAX_POLL; poll_count++) {
        status = poll_status();

        // bit1 should be topDoneSeen
        if (poll_count > 10 && (status & 0x2)) {
            break;
        }
    }

    fence_rw();

    acc_end = rdcycle();
    acc_cycles = acc_end - acc_start;

    // --------------------------------------------------------
    // Print results
    // --------------------------------------------------------
    printf("Final status = 0x%lx\n", status);
    printf("Poll count = %d\n", poll_count);
    printf("Software cycles = %lu\n", sw_cycles);
    printf("Accelerator cycles = %lu\n", acc_cycles);

    if (poll_count >= MAX_POLL) {
        printf("*** TIMEOUT ***\n");
        return 1;
    }

    print_nonzero_sw_out();
    print_nonzero_hw_out();

    int errors = compare_outputs_with_offset_scan();

    if (sw_cycles > 0) {
        if (acc_cycles < sw_cycles) {
            uint64_t improvement = ((sw_cycles - acc_cycles) * 100) / sw_cycles;
            printf("Improvement = %lu%%\n", improvement);
        } else {
            uint64_t slowdown = ((acc_cycles - sw_cycles) * 100) / sw_cycles;
            printf("Slowdown = %lu%%\n", slowdown);
        }
    }

    if (errors == 0) {
        printf("*** PASSED ***\n");
        return 0;
    } else {
        printf("*** PINPOINT DONE: OUTPUT MAPPING NEEDS ANALYSIS ***\n");
        return 0;
    }
}
