package matrix

import chisel3._
import chisel3.util._

class ConvEngine extends Module {
  val io = IO(new Bundle {
    // 接 LineBuffer（或测试台）
    val colIn       = Input(Vec(5, SInt(16.W)))
    val colValid    = Input(Bool())

    // 接 FSM（写 kernel）
    val kernelWe    = Input(Bool())
    val kernelAddr  = Input(UInt(5.W))
    val kernelData  = Input(SInt(16.W))

    // 输出
    val outValid    = Output(Bool())
    val result      = Output(SInt(16.W))

    // 反压：冻结流水线
    val stall       = Input(Bool())
  })

  // 实例化三个子模块
  val window = Module(new ShiftWindow)
  val kernel = Module(new KernelROM)
  val conv   = Module(new ConvUnit)

  // stall 门控：冻结时切断 colValid，ShiftWindow 不移位，ConvUnit 不进新数据
  val effectiveColValid = io.colValid && !io.stall

  // 外部 → ShiftWindow
  window.io.colIn    := io.colIn
  window.io.colValid := effectiveColValid

  // 外部 → KernelROM
  kernel.io.we    := io.kernelWe
  kernel.io.wAddr := io.kernelAddr
  kernel.io.wData := io.kernelData

  // ShiftWindow / KernelROM → ConvUnit
  conv.io.window  := window.io.window
  conv.io.kernel  := kernel.io.kernel
  // inValid 延迟 1 拍与 window 寄存器输出对齐
  conv.io.inValid := RegNext(effectiveColValid, false.B)

  // ConvUnit → 外部
  io.outValid := conv.io.outValid
  io.result   := conv.io.result
}
