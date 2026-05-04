package matrix

import chisel3._
import chisel3.util._

// 测试用的假内存，实现 SimpleMemIO 的另一端。
// 读延迟 1 cycle（Mem 组合读 + 寄存器）；写不产生响应。
// forceStall 引脚由 testbench 控制，模拟真实内存/总线的反压。
class FakeScratchpadMemory(depth: Int = 4096) extends Module {
  val io = IO(new Bundle {
    val mem        = Flipped(new SimpleMemIO) // 方向翻转：收请求、发响应
    val forceStall = Input(Bool())            // testbench 拉高时 req.ready 为低
  })

  val storage = Mem(depth, UInt(64.W)) // 组合读存储

  val respValid = RegInit(false.B)
  val respData  = RegInit(0.U(64.W))
  val respTag   = RegInit(0.U(4.W))

  io.mem.req.ready := !io.forceStall      //testBench拉高->内存不能收请求

  //握手成功
  when(io.mem.req.fire) {
    val wordAddr = io.mem.req.bits.addr(63, 3) // 字节地址 → word 索引
    when(io.mem.req.bits.isWrite) {
      storage.write(wordAddr, io.mem.req.bits.data)
    }.otherwise {
      respValid := true.B
      respData  := storage.read(wordAddr) // 组合读 → 寄存器捕获 → 下拍输出，1 cycle 延迟
      respTag   := io.mem.req.bits.tag
    }
  }

  when(io.mem.resp.fire) {
    respValid := false.B
  }

  io.mem.resp.valid      := respValid
  io.mem.resp.bits.data  := respData
  io.mem.resp.bits.tag   := respTag
}
