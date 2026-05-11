Matrix Convolution Accelerator Specification (Revised v1.1)

Part 1: English Version

1. Data Format & Arithmetic
Input Matrix Size: Fixed at 32x32.

Maximum Kernel Size: Fixed at 5x5.

Small Kernel Handling: Software Pre-padding (Strategy A). Hardware only supports 5x5 logic. Any smaller kernel must be zero-padded to 5x5 in memory by the CPU.

Fixed-point Format: Q8.8 (16-bit signed).

Accumulator: 32-bit SInt.

Saturation: Right-shift by 8 bits and saturate to 16-bit signed range at the end of the MAC tree before storing to output SRAM.

2. Boundary & Alignment
Padding: Same-padding (32x32 output) with Zero-padding value 0.

Memory Alignment:

ADDR_IN / ADDR_OUT: Must be 8-byte aligned (for 64-bit DMA efficiency).

ADDR_KER: Must be 2-byte aligned.

Violation triggers addr_err bit and aborts the FSM.

3. Instruction Set (RoCC custom0)
funct7	Name	        rs1	        rs2	    rd	    Description
0	    SET_ADDR_IN	    Base Addr	-	    -	    Input matrix address (8-byte align)
1	    SET_ADDR_KER	Base Addr	-	    -	    5x5 Kernel address (2-byte align)
2	    SET_ADDR_OUT	Base Addr	-	    -	    Output matrix address (8-byte align)
3	    START_ACCEL	    -	        -	    -	    Non-blocking start. Checks for addr != 0.
4	    POLL_STATUS	    -	        -	    Status	Bitfield status return (see below)

POLL_STATUS Bitfield (rd):
·bit[0]: Busy (1 = Accelerator is running)
·bit[1]: Done (1 = Calculation completed)
·bit[2]: Overflow (1 = Saturation occurred during calculation)
·bit[3]: Addr_Err (1 = Misaligned or null address detected)

4. Performance Targets
Latency: < 2500 cycles (Practical target including DMA overhead).

Speedup: >= 40x compared to pure software C implementation.