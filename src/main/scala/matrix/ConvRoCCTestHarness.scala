package matrix

import chisel3._
import chisel3.util._

/**
 * ⚠️  TEMPORARY TEST HARNESS - DO NOT USE IN FINAL IMPLEMENTATION
 *
 * This wrapper exists ONLY for standalone smoke testing on Mac
 * before migrating to real Chipyard LazyRoCC.
 *
 * Characteristics:
 *   - Uses hand-written RoCCCommand/RoCCResponse bundles (temporary)
 *   - Provides fake RoCC-like DecoupledIO for testing
 *   - Will be COMPLETELY REPLACED by LazyRoCCModuleImp in Chipyard
 *
 * When migrating to Chipyard:
 *   - DELETE this entire file
 *   - Keep ConvControl.scala (复用)
 *   - Write new LazyRoCCModuleImp wrapper (~30 lines)
 *   - Use real io.cmd/io.resp from Rocket Chip
 *
 * Status: Phase 2 standalone testing only
 * Lifetime: Until Chipyard integration in Phase 3+
 */
@deprecated("Temporary test harness, not final implementation", "Phase 2")
class ConvRoCCTestHarness extends Module {
  val io = IO(new RoCCTestIO)

  // ════════════════════════════════════════════════════════════════════════
  // Internal control module (long-term reusable core)
  // ════════════════════════════════════════════════════════════════════════
  val control = Module(new ConvControl)

  // ════════════════════════════════════════════════════════════════════════
  // Response pending registers (temporary wrapper-specific logic)
  // ════════════════════════════════════════════════════════════════════════
  val respPending = RegInit(false.B)
  val respData    = RegInit(0.U(64.W))
  val respRd      = RegInit(0.U(5.W))

  // ════════════════════════════════════════════════════════════════════════
  // Connection: io.cmd → control.instrCmd
  // ════════════════════════════════════════════════════════════════════════
  // TODO: Connect command handshake
  control.io.instrCmd.valid  := io.cmd.valid
  control.io.instrCmd.funct7 := io.cmd.bits.inst.funct7
  control.io.instrCmd.rs1    := io.cmd.bits.rs1
  control.io.instrCmd.rd     := io.cmd.bits.inst.rd
  io.cmd.ready               := control.io.instrReady && Mux(io.cmd.bits.inst.funct7 === 4.U, !respPending, true.B)

  // ════════════════════════════════════════════════════════════════════════
  // Instruction execution on cmd handshake(握手时候需要进行状态锁存)
  // ════════════════════════════════════════════════════════════════════════
  when(io.cmd.valid && io.cmd.ready) {
    when(io.cmd.bits.inst.funct7 === 4.U) {
      respPending := true.B
      respData := Cat(0.U(60.W),                                               
                      control.io.status.addrErr,                                  
                      control.io.status.overflow,                                 
                      control.io.status.done,                                     
                      control.io.status.busy) 
      respRd := io.cmd.bits.inst.rd   //结果写入x[rd]寄存器进行锁存                                  
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Response channel: hold resp.valid until resp.ready（锁存数据，直到CPU接收）
  // ════════════════════════════════════════════════════════════════════════

  //CPU接收了response，清除Pending
  when(io.resp.fire) {
    respPending := false.B
  }
  
  //resp通道由respPending寄存器驱动，保持到握手结束
  io.resp.valid    := respPending
  io.resp.bits.rd   := respRd
  io.resp.bits.data := respData

  // Status output
  io.busy      := control.io.status.busy
  io.interrupt := false.B
}

// ════════════════════════════════════════════════════════════════════════════
// Temporary Bundles (for standalone testing only, not final RoCC)
// ════════════════════════════════════════════════════════════════════════════

/**
 * Hand-written RoCC Command Bundle (temporary test harness)
 *
 * This mimics Rocket Chip's RoCC command structure for testing purposes.
 * NOT the final definition; will use real Rocket Chip types in Chipyard.
 */
class RoCCCommand extends Bundle {
  val inst = new Bundle {
    val funct7 = UInt(7.W)
    val rs2    = UInt(5.W)
    val rs1    = UInt(5.W)
    val xd     = Bool()
    val xs     = UInt(2.W)
    val rd     = UInt(5.W)
  }
  val rs1 = UInt(64.W)
  val rs2 = UInt(64.W)
}

/**
 * Hand-written RoCC Response Bundle (temporary test harness)
 *
 * This mimics Rocket Chip's RoCC response structure for testing purposes.
 * NOT the final definition; will use real Rocket Chip types in Chipyard.
 */
class RoCCResponse extends Bundle {
  val rd   = UInt(5.W)
  val data = UInt(64.W)
}

/**
 * Temporary RoCC-like I/O Interface (test harness only)
 *
 * Provides DecoupledIO-style cmd/resp handshake for standalone testing.
 * Will be replaced by Rocket Chip's real RoCC interface in Chipyard.
 */
class RoCCTestIO extends Bundle {
  val cmd       = Flipped(Decoupled(new RoCCCommand))
  val resp      = Decoupled(new RoCCResponse)
  val busy      = Output(Bool())
  val interrupt = Output(Bool())
}
