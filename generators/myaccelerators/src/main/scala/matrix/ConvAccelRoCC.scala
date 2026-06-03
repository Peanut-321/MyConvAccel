package matrix

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._

class ConvAccelRoCC(opcodes: OpcodeSet)(implicit p: Parameters)
  extends LazyRoCC(opcodes) {

  override lazy val module = new ConvAccelRoCCModule(this)
}

class ConvAccelRoCCModule(outer: ConvAccelRoCC)
  extends LazyRoCCModuleImp(outer) {

  val control = Module(new ConvControl)
  val accel = Module(new ConvAccelTop)

  val respPending = RegInit(false.B)
  val respData    = RegInit(0.U(64.W))
  val respRd      = RegInit(0.U(5.W))

  val topStartSeen = RegInit(false.B)
  val topEverActive = RegInit(false.B)
  val topDoneSeen = RegInit(false.B)


 //Default ConvAccelTop connections
  accel.io.start := false.B
  accel.io.inputAddr := control.io.addrConfig.in
  accel.io.kernelAddr := control.io.addrConfig.ker
  accel.io.outputAddr := control.io.addrConfig.out


 io.mem.req.valid := accel.io.mem.req.valid
 accel.io.mem.req.ready := io.mem.req.fire

 io.mem.req.bits := DontCare

 io.mem.req.bits.addr := accel.io.mem.req.bits.addr
 io.mem.req.bits.tag := accel.io.mem.req.bits.tag

 io.mem.req.bits.cmd := Mux(accel.io.mem.req.bits.isWrite, M_XWR, M_XRD)
 io.mem.req.bits.size := log2Ceil(8).U
 io.mem.req.bits.signed := false.B
 io.mem.req.bits.data := Mux(accel.io.mem.req.bits.isWrite, accel.io.mem.req.bits.data,0.U(64.W))
 io.mem.req.bits.phys := false.B
 io.mem.req.bits.dprv := 3.U

 io.mem.req.bits.no_xcpt := true.B
 io.mem.req.bits.no_alloc := false.B


 io.mem.req.bits.dv := false.B

 accel.io.mem.resp.valid := io.mem.resp.valid
 accel.io.mem.resp.bits.data :=  io.mem.resp.bits.data
 accel.io.mem.resp.bits.tag :=  io.mem.resp.bits.tag


  when(accel.io.state =/= 0.U){
      topEverActive := true.B
   }

     when(accel.io.done){
    topDoneSeen := true.B
  }

  io.cmd.ready := control.io.instrReady
  val cmdFire = io.cmd.valid && io.cmd.ready

  when(cmdFire){
    when (io.cmd.bits.inst.funct === 3.U){
      accel.io.start := true.B
      topStartSeen := true.B
      topDoneSeen:= false.B
      }
     }

  control.io.instrCmd.valid  := cmdFire
  control.io.instrCmd.funct7 := io.cmd.bits.inst.funct
  control.io.instrCmd.rs1    := io.cmd.bits.rs1
  control.io.instrCmd.rd     := io.cmd.bits.inst.rd



  when(cmdFire) {
    when(io.cmd.bits.inst.funct === 4.U) {
      respPending := true.B
      respData := Cat(
        0.U(62.W),
         topDoneSeen,
         1.U(1.W)

      )
      respRd := io.cmd.bits.inst.rd
    }
  }

  when(io.resp.fire) {
    respPending := false.B
  }



  when(io.mem.req.valid){
    }

  io.resp.valid     := respPending
  io.resp.bits.rd   := respRd
  io.resp.bits.data := respData

  io.busy := control.io.status.busy
  io.interrupt := false.B

  when(io.mem.resp.valid){
    printf(
    "[MEMRESP] tag=%d data=0x%x\n",
    io.mem.resp.bits.tag,
    io.mem.resp.bits.data)
    }
}
