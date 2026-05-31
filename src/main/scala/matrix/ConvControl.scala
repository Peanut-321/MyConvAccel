package matrix

import chisel3._
import chisel3.util._

/**
 * ConvControlIO — Phase 2 Control Interface
 *
 * Pure control logic interface (no RoCC specifics).
 * Can be reused in any RoCC-like wrapper or future Chipyard integration.
 */

class ConvControlIO extends Bundle {
  // Instruction input
  val instrCmd = new Bundle {
    val valid  = Input(Bool())
    val funct7 = Input(UInt(7.W))
    val rs1    = Input(UInt(64.W))   // rs1 value from CPU
    val rd     = Input(UInt(5.W))    // rd register number (for response)
  }
  val instrReady = Output(Bool())

  // Status query output
  val status = new Bundle {
    val busy    = Output(Bool())
    val done    = Output(Bool())
    val overflow = Output(Bool())
    val addrErr = Output(Bool())
  }

  // Config register query (for Phase 3+ DMA)
  val addrConfig = new Bundle {
    val in  = Output(UInt(64.W))
    val ker = Output(UInt(64.W))
    val out = Output(UInt(64.W))
  }
}

/**
 * ConvControl — Phase 2 Control Core Logic
 *
 * Implements:
 *   - funct7 instruction decode (0-4)
 *   - config registers (addrIn, addrKer, addrOut)
 *   - status bits (busy, done, overflow, addrErr)
 *   - FSM (sIdle, sBusy, sDone, sError)
 *   - address alignment check
 *   - fake busy counter
 *
 * This module is long-term reusable and will be migrated to Chipyard unchanged.
 *
 * Phase 2 does NOT implement:
 *   - Real DMA (io.mem)
 *   - MAC computation
 *   - LineBuffer / sliding window
 *   - Overflow detection (overflow always 0)
 */
class ConvControl extends Module {
  val io = IO(new ConvControlIO)

  // ════════════════════════════════════════════════════════════════════════
  // Config registers
  // ════════════════════════════════════════════════════════════════════════
  val addrIn  = RegInit(0.U(64.W))
  val addrKer = RegInit(0.U(64.W))
  val addrOut = RegInit(0.U(64.W))

  // ════════════════════════════════════════════════════════════════════════
  // Status bits
  // ════════════════════════════════════════════════════════════════════════
  val busy     = RegInit(false.B)
  val done     = RegInit(false.B)
  val overflow = RegInit(false.B)  // Phase 2: always 0
  val addrErr  = RegInit(false.B)

  // ════════════════════════════════════════════════════════════════════════
  // FSM
  // ════════════════════════════════════════════════════════════════════════
  val sIdle :: sBusy :: sDone :: sError :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // Fake busy counter (5-bit → 0-31, we use 0-20)
  val fakeCounter = RegInit(0.U(5.W))

  // ════════════════════════════════════════════════════════════════════════
  // Instruction decode
  // ════════════════════════════════════════════════════════════════════════
  val funct7       = io.instrCmd.funct7
  val doSetAddrIn  = funct7 === 0.U
  val doSetAddrKer = funct7 === 1.U
  val doSetAddrOut = funct7 === 2.U
  val doStart      = funct7 === 3.U
  val doPoll       = funct7 === 4.U
  val doSet        = doSetAddrIn || doSetAddrKer || doSetAddrOut

  // ════════════════════════════════════════════════════════════════════════
  // instrReady: state-dependent per instruction type
  // ════════════════════════════════════════════════════════════════════════
  // TODO: Implement cmd.ready logic based on:
  //   SET   (0-2): accept unless sBusy
  //   START (3):   accept only in sIdle or sDone
  //   POLL  (4):   always accept
  io.instrReady := MuxLookup(funct7, false.B, Seq(
    0.U -> (state =/= sBusy),
    1.U -> (state =/= sBusy),
    2.U -> (state =/= sBusy),
    3.U -> (state === sIdle || state === sDone),
    4.U -> true.B
  ))

  // ════════════════════════════════════════════════════════════════════════
  // Instruction execution on handshake (io.instrCmd.valid && io.instrReady)
  // ════════════════════════════════════════════════════════════════════════
  // 这里实现指令的译码
  // 如果是配置指令（funct7 = 0，1，2），那么对指令进行响应的存储
  // 如果指令是启动指令（funct7 = 3），那么对之前存入的地址进行合法性和对齐检查，若通过则启动，否则（sBusy or sError）
  when(io.instrCmd.valid && io.instrReady) {
    switch(funct7) {

      //这里0，1，2都是将CPU传来的地址保存在SET_ADDR_IN, SET_ADDR_KER, SET_ADDR_OUT中。
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

      //核心调度，Start_Accel
      is(3.U){    //若CPU传递的地址是0，或者字节不对齐
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

      is(4.U){ //POLL_Status

      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // FSM: per-cycle state transitions
  // ════════════════════════════════════════════════════════════════════════
  switch(state) {
    is(sIdle)  {
      /* wait for START */ 
    }

    is(sBusy)  {

      when(fakeCounter === 0.U){
        busy := false.B
        done := true.B
        state := sDone
      }.otherwise{
        fakeCounter := fakeCounter - 1.U
      }
    }

    is(sDone)  { 
      /* hold done=1 until next START */
    }

    is(sError) { 
      /* hold addrErr=1 until SET clears it */
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Status outputs
  // ════════════════════════════════════════════════════════════════════════
 // printf(
 // p"[CTRL] state=$state busy=$busy done=$done fake=$fakeCounter in=${Hexadecimal(addrIn)}\n"
 // )
  
  io.status.busy    := busy
  io.status.done    := done
  io.status.overflow := overflow
  io.status.addrErr := addrErr

  // ════════════════════════════════════════════════════════════════════════
  // Config register outputs
  // ════════════════════════════════════════════════════════════════════════
  io.addrConfig.in  := addrIn
  io.addrConfig.ker := addrKer
  io.addrConfig.out := addrOut
}
