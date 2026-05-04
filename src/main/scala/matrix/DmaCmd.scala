package matrix

import chisel3._

// DMA 操作码。Wrapper 将 CPU 的 RoCC 指令译码后填入 op 字段。
object DmaOp {
  val load_input   = 0.U(2.W)   // 从内存读输入矩阵，2048 bytes → 1024 个 16-bit 元素
  val load_kernel  = 1.U(2.W)   // 从内存读卷积核，64 bytes → 32 个 16-bit 元素
  val store_output = 2.U(2.W)   // 收计算结果，打包为 64-bit word 写回内存
}

// Wrapper 发给 DMA 的命令包。DMA 是消费者（cmd 端口加 Flipped）。
class DmaCmd extends Bundle {
  val op       = UInt(2.W)    // 操作类型，对应 DmaOp 的三个常量
  val baseAddr = UInt(64.W)   // 搬运起始字节地址（物理地址，由 CPU rs1 传入）
  val length   = UInt(16.W)   // 搬运字节数；kernel=64, input/output=2048
}

/*Wrapper解码funct7的命令格式*/