package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
 * ConvRoCCSpec — Phase 2 Smoke Tests
 *
 * Verifies RoCC control semantics via ConvRoCCTestHarness (standalone).
 *
 * What these tests verify:
 *   - Instruction decode (funct7 0-4)
 *   - Config register writes
 *   - Address alignment checks
 *   - FSM state transitions (sIdle/sBusy/sDone/sError)
 *   - Fake busy counter countdown
 *   - POLL_STATUS bitfield encoding
 *   - DecoupledIO handshake timing
 *   - respPending hold mechanism
 *
 * What these tests do NOT verify:
 *   - Real DMA (Phase 3)
 *   - MAC computation (Phase 4)
 *   - Integration with Rocket Chip (Chipyard)
 *
 * Result: "RoCC control semantics verified via standalone smoke test"
 */
class ConvRoCCSpec extends AnyFreeSpec with Matchers with ChiselSim {

  // ── Helper: 初始化所有输入为默认值 ──────────────────────────────────────────
  def initDut(dut: ConvRoCCTestHarness): Unit = {
    dut.io.cmd.valid.poke(false.B)
    dut.io.cmd.bits.inst.funct7.poke(0.U)
    dut.io.cmd.bits.inst.rd.poke(0.U)
    dut.io.cmd.bits.inst.xd.poke(false.B)
    dut.io.cmd.bits.rs1.poke(0.U)
    dut.io.resp.ready.poke(false.B)
  }

  // ── Helper: 发送一条指令（步进一个 cycle，不检查 ready）──────────────────────
  def sendCmd(dut: ConvRoCCTestHarness, funct7: Int, rs1: Long = 0L, rd: Int = 0): Unit = {
    dut.io.cmd.valid.poke(true.B)
    dut.io.cmd.bits.inst.funct7.poke(funct7.U)
    dut.io.cmd.bits.rs1.poke(rs1.U)
    dut.io.cmd.bits.inst.rd.poke(rd.U)
    dut.clock.step()
    dut.io.cmd.valid.poke(false.B)
  }

  // ── Helper: 发送 POLL，验证 resp，消费 response ──────────────────────────────
  // expectedData 对应 bitfield: bit[0]=busy, bit[1]=done, bit[2]=overflow, bit[3]=addrErr
  def pollAndExpect(dut: ConvRoCCTestHarness, expectedData: Int): Unit = {
    dut.io.cmd.valid.poke(true.B)
    dut.io.cmd.bits.inst.funct7.poke(4.U)
    dut.io.cmd.bits.inst.rd.poke(1.U)
    dut.clock.step()                               // POLL 握手 → respPending=1
    dut.io.cmd.valid.poke(false.B)
    dut.io.resp.valid.expect(true.B)               // resp 有效
    dut.io.resp.bits.data.expect(expectedData.U)   // status 正确
    dut.io.resp.ready.poke(true.B)
    dut.clock.step()                               // resp 握手 → respPending=0
    dut.io.resp.ready.poke(false.B)
  }

  // ── Helper: 设置三个合法地址 ──────────────────────────────────────────────────
  def setupValidAddresses(dut: ConvRoCCTestHarness): Unit = {
    sendCmd(dut, 0, 0x1000L)  // SET_ADDR_IN  (8-byte aligned)
    sendCmd(dut, 1, 0x2000L)  // SET_ADDR_KER (2-byte aligned)
    sendCmd(dut, 2, 0x3000L)  // SET_ADDR_OUT (8-byte aligned)
  }

  // ════════════════════════════════════════════════════════════════════════════

  "ConvRoCC control logic should" - {

    // ── TC-01 ────────────────────────────────────────────────────────────────
    "TC-01: SET_ADDR_IN handshake and no response" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)

        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(0.U)
        dut.io.cmd.bits.rs1.poke(0x1000.U)

        dut.io.cmd.ready.expect(true.B)    // sIdle + funct7=0 → ready
        dut.io.resp.valid.expect(false.B)  // SET 不返回 response

        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        dut.io.busy.expect(false.B)        // SET 不改变 busy
      }
    }

    // ── TC-02 ────────────────────────────────────────────────────────────────
    "TC-02: SET_ADDR_KER handshake and no response" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)

        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(1.U)
        dut.io.cmd.bits.rs1.poke(0x2000.U)

        dut.io.cmd.ready.expect(true.B)
        dut.io.resp.valid.expect(false.B)

        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        dut.io.busy.expect(false.B)
      }
    }

    // ── TC-03 ────────────────────────────────────────────────────────────────
    "TC-03: SET_ADDR_OUT handshake and no response" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)

        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(2.U)
        dut.io.cmd.bits.rs1.poke(0x3000.U)

        dut.io.cmd.ready.expect(true.B)
        dut.io.resp.valid.expect(false.B)

        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        dut.io.busy.expect(false.B)
      }
    }

    // ── TC-04 ────────────────────────────────────────────────────────────────
    "TC-04: START_ACCEL success path (valid addresses)" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        setupValidAddresses(dut)

        // START
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(3.U)
        dut.io.cmd.ready.expect(true.B)   // START accepted in sIdle
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // 立即：busy=1, done=0, addrErr=0
        dut.io.busy.expect(true.B)

        // 等待 fake counter 倒计时（21 个 sBusy cycles）
        dut.clock.step(21)

        // done=1, busy=0
        dut.io.busy.expect(false.B)
        pollAndExpect(dut, 2)  // bit[1]=1 → done=1
      }
    }

    // ── TC-05 ────────────────────────────────────────────────────────────────
    "TC-05: START_ACCEL fails when addrIn is 0" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        // 不设置 addrIn（保持默认值 0）
        sendCmd(dut, 1, 0x2000L)  // SET_ADDR_KER
        sendCmd(dut, 2, 0x3000L)  // SET_ADDR_OUT

        sendCmd(dut, 3)  // START → inBad (addrIn=0)

        dut.io.busy.expect(false.B)
        pollAndExpect(dut, 8)  // bit[3]=1 → addrErr=1
      }
    }

    // ── TC-06 ────────────────────────────────────────────────────────────────
    "TC-06: START_ACCEL fails when addrIn not 8-byte aligned" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        sendCmd(dut, 0, 0x1001L)  // addrIn 低3位非0
        sendCmd(dut, 1, 0x2000L)
        sendCmd(dut, 2, 0x3000L)

        sendCmd(dut, 3)  // START → inBad

        dut.io.busy.expect(false.B)
        pollAndExpect(dut, 8)  // addrErr=1
      }
    }

    // ── TC-07 ────────────────────────────────────────────────────────────────
    "TC-07: START_ACCEL fails when addrKer not 2-byte aligned" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        sendCmd(dut, 0, 0x1000L)
        sendCmd(dut, 1, 0x2001L)  // addrKer bit[0]=1
        sendCmd(dut, 2, 0x3000L)

        sendCmd(dut, 3)  // START → kerBad

        dut.io.busy.expect(false.B)
        pollAndExpect(dut, 8)  // addrErr=1
      }
    }

    // ── TC-08 ────────────────────────────────────────────────────────────────
    "TC-08: POLL_STATUS returns all-zero in idle state" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        // reset 后直接 POLL
        pollAndExpect(dut, 0)  // busy=0, done=0, overflow=0, addrErr=0
      }
    }

    // ── TC-09 ────────────────────────────────────────────────────────────────
    "TC-09: POLL_STATUS returns busy=1 during busy state" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        setupValidAddresses(dut)
        sendCmd(dut, 3)  // START → sBusy

        dut.io.busy.expect(true.B)
        pollAndExpect(dut, 1)  // bit[0]=1 → busy=1
      }
    }

    // ── TC-10 ────────────────────────────────────────────────────────────────
    "TC-10: POLL_STATUS returns done=1 after completion" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        setupValidAddresses(dut)
        sendCmd(dut, 3)      // START
        dut.clock.step(21)   // 等待 fake counter 结束

        dut.io.busy.expect(false.B)
        pollAndExpect(dut, 2)  // bit[1]=1 → done=1
      }
    }

    // ── TC-11 ────────────────────────────────────────────────────────────────
    "TC-11: POLL_STATUS returns addrErr=1 after address error" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        sendCmd(dut, 1, 0x2000L)
        sendCmd(dut, 2, 0x3000L)
        sendCmd(dut, 3)  // START → addrIn=0 → addrErr

        pollAndExpect(dut, 8)  // bit[3]=1 → addrErr=1
      }
    }

    // ── TC-12 ────────────────────────────────────────────────────────────────
    "TC-12: Complete SET→START→busy→done sequence" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        setupValidAddresses(dut)
        sendCmd(dut, 3)  // START

        // 刚 START：busy=1
        dut.io.busy.expect(true.B)

        // 中途（step 10 次）：仍然 busy
        dut.clock.step(10)
        dut.io.busy.expect(true.B)

        // 再 step 11 次（总共 21）：done=1
        dut.clock.step(11)
        dut.io.busy.expect(false.B)

        pollAndExpect(dut, 2)  // done=1
      }
    }

    // ── TC-13 ────────────────────────────────────────────────────────────────
    "TC-13: SET_ADDR is blocked while busy" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)
        setupValidAddresses(dut)
        sendCmd(dut, 3)  // START → sBusy

        // 在 sBusy 中尝试 SET_ADDR_IN
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(0.U)
        dut.io.cmd.bits.rs1.poke(0x5000.U)

        dut.io.cmd.ready.expect(false.B)  // 被阻塞

        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // busy 仍然为 1（SET 没有被接受）
        dut.io.busy.expect(true.B)
      }
    }

    // ── respPending mechanism ─────────────────────────────────────────────────
    "respPending: holds resp.valid until resp.ready" in {
      simulate(new ConvRoCCTestHarness) { dut =>
        initDut(dut)

        // 发 POLL
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.inst.funct7.poke(4.U)
        dut.io.cmd.bits.inst.rd.poke(1.U)
        dut.clock.step()                   // POLL 握手 → respPending=1
        dut.io.cmd.valid.poke(false.B)

        // resp.valid=1，但 CPU 还没 ready
        dut.io.resp.valid.expect(true.B)
        dut.io.resp.ready.poke(false.B)
        dut.clock.step()

        // resp.valid 保持为 1
        dut.io.resp.valid.expect(true.B)

        // CPU ready → 握手完成
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.resp.ready.poke(false.B)

        // 握手完成后 resp.valid 变回 0
        dut.io.resp.valid.expect(false.B)
      }
    }
  }
}
