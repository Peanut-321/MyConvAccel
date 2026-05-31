#include <stdint.h>
#include <stdio.h>
#include "rocc.h"

#define INPUT_W 32
#define INPUT_H 32
#define INPUT_SIZE 1024

#define KERNEL_W 5
#define KERNEL_H 5
#define KERNEL_SIZE 25

#define KERNEL_HW_SIZE 32
#define HW_OUT_SIZE 1088
#define OUT_SIZE 1024

#define MAX_POLL 10000

static int16_t input[INPUT_SIZE]       __attribute__((aligned(8)));
static int16_t kernel[KERNEL_HW_SIZE]  __attribute__((aligned(8)));
static int16_t sw_out[OUT_SIZE]        __attribute__((aligned(8)));
static int16_t hw_out[HW_OUT_SIZE]     __attribute__((aligned(8)));

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

static inline void set_ksize(uint64_t ksize) {
  ROCC_INSTRUCTION_SS(0, ksize, 0, 5);
}

static inline uint64_t rdcycle(void) {
  uint64_t cycles;
  asm volatile ("rdcycle %0" : "=r"(cycles));
  return cycles;
}

static inline void fence_rw(void) {
  asm volatile ("fence rw, rw" ::: "memory");
}

static int16_t sat16(int32_t x) {
  if (x > 32767) return 32767;
  if (x < -32768) return -32768;
  return (int16_t)x;
}

static void clear_all(void) {
  for (int i = 0; i < INPUT_SIZE; i++) input[i] = 0;
  for (int i = 0; i < OUT_SIZE; i++) sw_out[i] = 0;
  for (int i = 0; i < HW_OUT_SIZE; i++) hw_out[i] = 0;
  for (int i = 0; i < KERNEL_HW_SIZE; i++) kernel[i] = 0;
}

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

      sw_out[r * INPUT_W + c] = sat16(acc >> 8);
    }
  }
}

static uint64_t run_accel(void) {
  uint64_t status = 0;
  int poll_count = 0;

  set_addr_in((uint64_t)(uintptr_t)input);
  set_addr_ker((uint64_t)(uintptr_t)kernel);
  set_addr_out((uint64_t)(uintptr_t)hw_out);
  //set_ksize(5);

  fence_rw();

  uint64_t acc_start = rdcycle();
  start_accel();

  for (poll_count = 0; poll_count < MAX_POLL; poll_count++) {
    status = poll_status();

    if (poll_count > 10 && (status & 0x2)) {
      break;
    }
  }

  fence_rw();

  uint64_t acc_end = rdcycle();

  printf("Final status = 0x%lx\n", status);
  printf("Poll count = %d\n", poll_count);

  if (poll_count >= MAX_POLL) {
    printf("*** TIMEOUT ***\n");
  }

  return acc_end - acc_start;
}

static int compare_outputs(const char *test_name) {
  int mismatches = 0;
  int sw_nonzero = 0;
  int hw_nonzero = 0;

  for (int i = 0; i < OUT_SIZE; i++) {
    if (sw_out[i] != 0) sw_nonzero++;
    if (hw_out[i] != 0) hw_nonzero++;

    if (sw_out[i] != hw_out[i]) {
      if (mismatches < 20) {
        printf("[MISMATCH] %s idx=%d sw=%d hw=%d\n",
               test_name, i, sw_out[i], hw_out[i]);
      }
      mismatches++;
    }
  }

  printf("[%s] sw_nonzero=%d hw_nonzero=%d mismatches=%d\n",
         test_name, sw_nonzero, hw_nonzero, mismatches);

  if (mismatches == 0) {
    printf("[PASS] %s\n", test_name);
  } else {
    printf("[FAIL] %s\n", test_name);
  }

  return mismatches;
}

static int run_test(const char *test_name) {
  printf("\n==============================\n");
  printf("TEST: %s\n", test_name);
  printf("==============================\n");
  
  uint64_t sw_cycles = 0;
//  printf("[DBG] before software conv\n");
//  uint64_t sw_start = rdcycle();
//  software_conv_5x5_same_q88();
//  uint64_t sw_end = rdcycle();
//  printf("[DBG] after software conv\n");

//  uint64_t sw_cycles = sw_end - sw_start;

  printf("[DBG] before run_accel\n");
  uint64_t acc_cycles = run_accel();
  printf("[DBG] after run_accel\n");


//  uint64_t sw_start = rdcycle();
//  software_conv_5x5_same_q88();
//  uint64_t sw_end = rdcycle();
//  uint64_t sw_cycles = sw_end - sw_start;

//  uint64_t acc_cycles = run_accel();

  printf("Software cycles = %lu\n", sw_cycles);
  printf("Accelerator cycles = %lu\n", acc_cycles);

  if (sw_cycles > 0 && acc_cycles < sw_cycles) {
    uint64_t improvement = ((sw_cycles - acc_cycles) * 100) / sw_cycles;
    printf("Improvement = %lu%%\n", improvement);
  }

  return compare_outputs(test_name);
}

int main(void) {
  int total_errors = 0;

  printf("[UART] CNN accelerator correctness benchmark start\n");
  printf("input addr  = 0x%lx\n", (uint64_t)(uintptr_t)input);
  printf("kernel addr = 0x%lx\n", (uint64_t)(uintptr_t)kernel);
  printf("hw_out addr = 0x%lx\n", (uint64_t)(uintptr_t)hw_out);


  
  clear_all();
  input[0] = 256;
  kernel[12] = 256;
  sw_out[0] = 256;
  total_errors += run_test("top_left");
  
    for(int i=0;i<HW_OUT_SIZE;i++){
    if(hw_out[i]!=0)
      printf("hw[%d]=%d\n",i,hw_out[i]);
   }
   
 
  


 
  printf("\n==============================\n");
  if (total_errors == 0) {
    printf("*** ALL TESTS PASSED ***\n");
  } else {
    printf("*** TESTS FAILED: total mismatches = %d ***\n", total_errors);
  }
  printf("==============================\n");

  return 0;
}

