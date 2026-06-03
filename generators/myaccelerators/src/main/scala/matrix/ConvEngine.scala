package matrix

import chisel3._
import chisel3.util._

class ConvEngine extends Module {
  val io = IO(new Bundle {
    val colIn       = Input(Vec(5, SInt(16.W)))
    val colValid    = Input(Bool())

    val kernelWe    = Input(Bool())
    val kernelAddr  = Input(UInt(5.W))
    val kernelData  = Input(SInt(16.W))

    val outValid    = Output(Bool())
    val result      = Output(SInt(16.W))

    val stall       = Input(Bool())
  })

  val window = Module(new ShiftWindow)
  val kernel = Module(new KernelROM)
  val conv   = Module(new ConvUnit)

  val effectiveColValid = io.colValid && !io.stall

  // external -> ShiftWindow
  window.io.colIn    := io.colIn
  window.io.colValid := effectiveColValid

  // external -> KernelROM
  kernel.io.we    := io.kernelWe
  kernel.io.wAddr := io.kernelAddr
  kernel.io.wData := io.kernelData

  // ShiftWindow / KernelROM -> ConvUnit
  conv.io.window  := window.io.window
  conv.io.kernel  := kernel.io.kernel
  // inValid 1-cycle delay to align with registered window output
  conv.io.inValid := RegNext(effectiveColValid, false.B)

  // ConvUnit -> external
  io.outValid := conv.io.outValid
  io.result   := conv.io.result
}
