package matrix

import chisel3._
import chisel3.util._

class SimpleMemReq extends Bundle {
  val addr    = UInt(64.W)
  val data    = UInt(64.W)
  val isWrite = Bool()
  val tag     = UInt(8.W)
  val mask    = UInt(8.W)
}

class SimpleMemResp extends Bundle {
  val data = UInt(64.W)
  val tag  = UInt(8.W)
}

class SimpleMemIO extends Bundle {
  val req  = Decoupled(new SimpleMemReq)
  val resp = Flipped(Decoupled(new SimpleMemResp))
}
