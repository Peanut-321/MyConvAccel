package matrix

import chisel3._
import chisel3.util._

class ConvControlIO extends Bundle {
  val instrCmd = new Bundle {
    val valid  = Input(Bool())
    val funct7 = Input(UInt(7.W))
    val rs1    = Input(UInt(64.W))
    val rd     = Input(UInt(5.W))
  }
  val instrReady = Output(Bool())

  val status = new Bundle {
    val busy    = Output(Bool())
    val done    = Output(Bool())
    val overflow = Output(Bool())
    val addrErr = Output(Bool())
  }

  val addrConfig = new Bundle {
    val in  = Output(UInt(64.W))
    val ker = Output(UInt(64.W))
    val out = Output(UInt(64.W))
  }
}

class ConvControl extends Module {
  val io = IO(new ConvControlIO)

  val addrIn  = RegInit(0.U(64.W))
  val addrKer = RegInit(0.U(64.W))
  val addrOut = RegInit(0.U(64.W))

  val busy     = RegInit(false.B)
  val done     = RegInit(false.B)
  val overflow = RegInit(false.B)
  val addrErr  = RegInit(false.B)

  val sIdle :: sBusy :: sDone :: sError :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val fakeCounter = RegInit(0.U(5.W))

  val funct7       = io.instrCmd.funct7
  val doSetAddrIn  = funct7 === 0.U
  val doSetAddrKer = funct7 === 1.U
  val doSetAddrOut = funct7 === 2.U
  val doStart      = funct7 === 3.U
  val doPoll       = funct7 === 4.U

  io.instrReady := MuxLookup(funct7, false.B, Seq(
    0.U -> (state =/= sBusy),
    1.U -> (state =/= sBusy),
    2.U -> (state =/= sBusy),
    3.U -> (state === sIdle || state === sDone),
    4.U -> true.B
  ))

  when(io.instrCmd.valid && io.instrReady) {
    switch(funct7) {
      is(0.U){
        addrIn := io.instrCmd.rs1
        when(state === sError){
          addrErr := false.B
          state := sIdle
        }
      }
      is(1.U){
        addrKer := io.instrCmd.rs1
        when(state === sError){
          addrErr := false.B
          state := sIdle
        }
      }
      is(2.U){
        addrOut := io.instrCmd.rs1
        when(state === sError){
          addrErr := false.B
          state := sIdle
        }
      }
      is(3.U){
        val inBad = (addrIn === 0.U || addrIn(2, 0) =/= 0.U)
        val kerBad = (addrKer === 0.U || addrKer(0) =/= 0.U)
        val outBad = (addrOut === 0.U || addrOut(2, 0) =/= 0.U)
        when(inBad || kerBad || outBad){
          addrErr := true.B
          busy    := false.B
          done    := false.B
          state   := sError
        }.otherwise{
          addrErr     := false.B
          busy        := true.B
          done        := false.B
          fakeCounter := 20.U
          state       := sBusy
        }
      }
      is(4.U){ /* POLL */ }
    }
  }

  switch(state) {
    is(sIdle)  { /* wait for START */ }
    is(sBusy)  {
      when(fakeCounter === 0.U){
        busy := false.B
        done := true.B
        state := sDone
      }.otherwise{
        fakeCounter := fakeCounter - 1.U
      }
    }
    is(sDone)  { /* hold done=1 until next START */ }
    is(sError) { /* hold addrErr=1 until SET clears it */ }
  }

  io.status.busy    := busy
  io.status.done    := done
  io.status.overflow := overflow
  io.status.addrErr := addrErr

  io.addrConfig.in  := addrIn
  io.addrConfig.ker := addrKer
  io.addrConfig.out := addrOut
}
