package matrix

import chisel3._
import chisel3.util._

// DMA operation codes
object DmaOp {
  val load_kernel  = 0.U(2.W)
  val load_input   = 1.U(2.W)
  val store_output = 2.U(2.W)
}

class DmaCmd extends Bundle {
  val op       = UInt(2.W)
  val baseAddr = UInt(64.W)
  val length   = UInt(16.W)
}
