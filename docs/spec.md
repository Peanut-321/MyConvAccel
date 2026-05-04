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


------------------------------------------------------------------

1. 数据格式与运算
输入矩阵尺寸: 固定为 32x32。

最大卷积核尺寸: 固定为 5x5。

小尺寸核处理: 软件预填充 (方案 A)。硬件仅实现 5x5 逻辑。若需 3x3 卷积，CPU 需在内存中将其预先填充至 5x5。

定点数格式: Q8.8 (16位有符号整数)。

累加器: 32位有符号数 (SInt)。

饱和处理: 在乘加树末端进行右移 8位，并执行 16位有符号数饱和截断后存入 SRAM。

2. 边界与对齐
填充模式: Same-padding (32x32 输出)，零填充 数值为 0。

内存对齐:

ADDR_IN / ADDR_OUT: 必须 8字节对齐（提升 64位 DMA 效率）。

ADDR_KER: 必须 2字节对齐。

违反对齐要求将触发 addr_err 标志并中止状态机。

3. 指令集设计 (RoCC custom0)
funct7	指令名称	rs1内容	rs2内容	rd内容	详细说明
0	SET_ADDR_IN	输入基址	-	-	输入矩阵地址 (8字节对齐)
1	SET_ADDR_KER	卷积核基址	-	-	5x5 卷积核 (2字节对齐)
2	SET_ADDR_OUT	输出基址	-	-	输出矩阵地址 (8字节对齐)
3	START_ACCEL	-	-	-	非阻塞启动。检查地址是否非空。
4	POLL_STATUS	-	-	状态位	返回位域状态（见下文）
POLL_STATUS 状态位域 (rd):

bit[0]: Busy (1 = 加速器正在运行)

bit[1]: Done (1 = 计算已完成)

bit[2]: Overflow (1 = 计算过程中发生过饱和截断)

bit[3]: Addr_Err (1 = 检测到地址未对齐或为空)

4. 性能目标
预期周期: 小于 2500 周期（含 DMA 搬运开销的实测目标）。

加速比: 相比纯软件 C 语言实现提升 40倍以上。