package matrix

import chisel3._
import chisel3.util._

// DMA 向内存发起的请求。DMA 驱动 valid 和 bits，是生产者，不加 Flipped。
class SimpleMemReq extends Bundle {
  val addr    = UInt(64.W)   // 读写地址（字节粒度，物理地址）
  val data    = UInt(64.W)   // 要写入的 64-bit 数据（读请求时忽略）
  val isWrite = Bool()       // true = 写入，false = 读取
  val tag     = UInt(4.W)    // 请求编号，Phase 3 串行 DMA 始终填 0
  val mask    = UInt(8.W)    // 字节使能：bit0→data[7:0], bit7→data[63:56]，1 表示写入该字节，全字读写填 0xFF
}

// 内存返回的响应。内存驱动 valid 和 bits，DMA 是消费者，由 SimpleMemIO 统一用 Flipped 翻转方向。
class SimpleMemResp extends Bundle {
  val data = UInt(64.W)      // 读回的 64-bit 数据
  val tag  = UInt(4.W)       // 对应的请求 tag，用于匹配
}

// ConvDMA 和内存之间的解耦接口。
// 测试时 → FakeScratchpadMemory，集成时 → HellaCacheAdapter。
// ConvDMA 只认这个接口，背后换什么实现都不需要改代码。

class SimpleMemIO extends Bundle {
  val req  = Decoupled(new SimpleMemReq)           // DMA → Memory
  val resp = Flipped(Decoupled(new SimpleMemResp)) // Memory → DMA
}

/*定义DMA <-> 内存的通信协议*/