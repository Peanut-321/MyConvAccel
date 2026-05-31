
#include <stdint.h>
#include <stdio.h>
#include"rocc.h"

//#define STR1(x) #x
//#define STR(x) STR1(x)

//#define ROCC_INSTRUCTION_DSS(X, rd, rs1, rs2, funct) \
  asm volatile (".insn r 0x0b, 0, %3, %0, %1, %2" \
                : "=r"(rd) \
                : "r"(rs1), "r"(rs2), "i"(funct))

//#define ROCC_INSTRUCTION_SS(X, rs1, rs2, funct) \
  asm volatile (".insn r 0x0b, 0, %2, x0, %0, %1" \
                :: "r"(rs1), "r"(rs2), "i"(funct))


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


static inline uint64_t rdcycle() {
    uint64_t cycles;
    asm volatile ("rdcycle %0" : "=r"(cycles));
    return cycles;
}

int main(void) {

    uint64_t start, end;
    uint64_t status = 0;

    set_addr_in(0x80000000ULL);
    set_addr_ker(0x80001000ULL);
    set_addr_out(0x80002000ULL);

    start = rdcycle();

    start_accel();

    for (int i = 0; i < 1000; i++) {
        status = poll_status();

        if (status & 0x2) {
            break;
        }
    }

    end = rdcycle();

    printf("Final status = 0x%lx\n", status);
    printf("Accel cycles = %lu\n", end - start);

    if (status & 0x2) {
        printf("*** PASSED ***\n");
    } else {
        printf("*** TIMEOUT ***\n");
    }

    return 0;
}

