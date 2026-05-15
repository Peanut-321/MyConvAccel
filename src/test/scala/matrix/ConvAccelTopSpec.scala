package matrix

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.io.Source

/**
 * ConvAccelTopTestHarness — 测试包装器
 * 连接 ConvAccelTop + FakeScratchpadMemory，提供 prefill / readback 端口。
 */
class ConvAccelTopTestHarness extends Module {
  val top = Module(new ConvAccelTop)
  val mem = Module(new FakeScratchpadMemory(4096))

  val io = IO(new Bundle {
    val start        = Input(Bool())
    val done         = Output(Bool())
    val kernelAddr   = Input(UInt(64.W))
    val inputAddr    = Input(UInt(64.W))
    val outputAddr   = Input(UInt(64.W))
    val prefill      = Flipped(Decoupled(new SimpleMemReq))
    val readback     = Flipped(Decoupled(new SimpleMemReq))  // 请求读
    val readbackResp = Decoupled(new SimpleMemResp)          // 读响应
    val forceStall   = Input(Bool())
    // debug
    val state        = Output(UInt(3.W))
    val dbgResultCnt = Output(UInt(11.W))
    val dbgQueueCnt  = Output(UInt(7.W))
    val dbgDmaDone   = Output(Bool())
    val dbgDmaState  = Output(UInt(3.W))
  })

  // ── 内存总线仲裁 ──
  // 优先级：prefill > readback > top(DMA)
  val doPrefill  = io.prefill.valid
  val doReadback = io.readback.valid

  // readback 响应有 1 cycle 延迟，用 pending 寄存器保持响应通路
  val readbackPending = RegInit(false.B)
  when (io.readback.fire)           { readbackPending := true.B  }
  when (io.readbackResp.fire)       { readbackPending := false.B }

  val useTop     = !doPrefill && !doReadback && !readbackPending

  mem.io.mem.req.valid := Mux(doPrefill,  io.prefill.valid,
                            Mux(doReadback, io.readback.valid,
                              top.io.mem.req.valid))
  mem.io.mem.req.bits := Mux(doPrefill,  io.prefill.bits,
                           Mux(doReadback, io.readback.bits,
                             top.io.mem.req.bits))
  io.prefill.ready  := Mux(doPrefill,  mem.io.mem.req.ready, false.B)
  io.readback.ready := Mux(doReadback, mem.io.mem.req.ready, false.B)
  top.io.mem.req.ready := Mux(useTop,  mem.io.mem.req.ready, false.B)

  // 响应路由：readback 需要 pending，因为响应延迟 1 cycle
  top.io.mem.resp.valid     := useTop && mem.io.mem.resp.valid
  top.io.mem.resp.bits      := mem.io.mem.resp.bits
  io.readbackResp.valid     := (doReadback || readbackPending) && mem.io.mem.resp.valid
  io.readbackResp.bits      := mem.io.mem.resp.bits
  mem.io.mem.resp.ready     := Mux(useTop,     top.io.mem.resp.ready,
                                 Mux(doReadback || readbackPending, io.readbackResp.ready, false.B))

  // ── 反压控制 ──
  mem.io.forceStall := io.forceStall

  // ── 地址 / 控制 ──
  top.io.start      := io.start
  io.done           := top.io.done
  top.io.kernelAddr := io.kernelAddr
  top.io.inputAddr  := io.inputAddr
  top.io.outputAddr := io.outputAddr

  // ── debug ──
  io.state        := top.io.state
  io.dbgResultCnt := top.io.dbgResultCnt
  io.dbgQueueCnt  := top.io.dbgQueueCnt
  io.dbgDmaDone   := top.io.dbgDmaDone
  io.dbgDmaState  := top.io.dbgDmaState
}


/**
 * ConvAccelTopSpec — Phase 7 端到端测试。
 *
 * 用 identity_kernel 做 golden 比对：
 *   1. 预填 kernel (0x1000) + input (0x2000) 到 FakeScratchpadMemory
 *   2. 启动 ConvAccelTop
 *   3. 等 done
 *   4. 读回 output (0x3000)，与预期 hex 比对
 */
class ConvAccelTopSpec extends AnyFreeSpec with Matchers with ChiselSim {

  def readHex(path: String): Seq[Int] = {
    val src = Source.fromFile(path)
    try {
      src.getLines().toSeq.map { line =>
        val v = Integer.parseInt(line.trim(), 16)
        if ((v & 0x8000) != 0) v | 0xFFFF0000 else v
      }
    } finally src.close()
  }

  val resPath = "src/test/resources/"

  // 将 16-bit hex 值列表打包为 64-bit word 数组（小端序，4 elem/word）
  def packWords(elems: Seq[Int], padToWords: Int): Array[Long] = {
    val padded = elems.padTo(padToWords * 4, 0)
    Array.tabulate(padToWords) { i =>
      val base = i * 4
      (padded(base)     & 0xFFFF).toLong          |
      ((padded(base + 1) & 0xFFFF).toLong << 16)  |
      ((padded(base + 2) & 0xFFFF).toLong << 32)  |
      ((padded(base + 3) & 0xFFFF).toLong << 48)
    }
  }

  // 从 64-bit word 数组解包为 16-bit 值列表
  def unpackWords(words: Seq[Long], numElems: Int): Seq[Int] = {
    words.take((numElems + 3) / 4).flatMap { w =>
      Seq(
        (w         & 0xFFFF).toInt,
        ((w >> 16) & 0xFFFF).toInt,
        ((w >> 32) & 0xFFFF).toInt,
        ((w >> 48) & 0xFFFF).toInt
      ).map { v => if ((v & 0x8000) != 0) v | 0xFFFF0000 else v }
    }.take(numElems)
  }

  // 通过 prefill 端口写数据到 FakeScratchpadMemory
  def prefillMemory(dut: ConvAccelTopTestHarness, baseAddr: Long, data: Array[Long]): Unit = {
    for (i <- data.indices) {
      dut.io.prefill.valid.poke(true.B)
      dut.io.prefill.bits.addr.poke((baseAddr + i * 8).U(64.W))
      dut.io.prefill.bits.data.poke(data(i).U(64.W))
      dut.io.prefill.bits.isWrite.poke(true.B)
      dut.io.prefill.bits.tag.poke(0.U(4.W))
      dut.io.prefill.bits.mask.poke(0xFF.U(8.W))
      dut.io.prefill.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.prefill.valid.poke(false.B)
  }

  // 通过 readback 端口从 FakeScratchpadMemory 读回数据
  def readbackMemory(dut: ConvAccelTopTestHarness, baseAddr: Long, numWords: Int): Seq[Long] = {
    var words = Seq.empty[Long]
    for (i <- 0 until numWords) {
      // 发读请求
      dut.io.readback.valid.poke(true.B)
      dut.io.readback.bits.addr.poke((baseAddr + i * 8).U(64.W))
      dut.io.readback.bits.isWrite.poke(false.B)
      dut.io.readback.bits.tag.poke(0.U(4.W))
      dut.io.readback.bits.mask.poke(0xFF.U(8.W))
      dut.io.readback.bits.data.poke(0.U(64.W))
      // 握手
      dut.io.readback.ready.expect(true.B)
      dut.clock.step()
      dut.io.readback.valid.poke(false.B)

      // 等响应（FakeScratchpadMemory 延迟 1 cycle）
      // 先确认响应未就绪（可能是上一拍的残响）
      while (!dut.io.readbackResp.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      val data = dut.io.readbackResp.bits.data.peek().litValue.toLong
      words = words :+ data
      dut.io.readbackResp.ready.poke(true.B)
      dut.clock.step()
      dut.io.readbackResp.ready.poke(false.B)
    }
    words
  }

  // ── 运行加速器直到 done，返回总 cycles ──
  def runUntilDone(dut: ConvAccelTopTestHarness, watchdog: Int = 4000): Int = {
    var cycle = 0
    var done  = false
    while (!done && cycle < watchdog) {
      done = dut.io.done.peek().litToBoolean
      dut.clock.step()
      cycle += 1
    }
    cycle
  }

  // ── 比对 32×32 输出：每行跳过前2个bubble ──
  def verifyOutput(outputElems: Seq[Int], expectedHex: Seq[Int]): Int = {
    var mismatch = 0
    for (row <- 0 until 32) {
      for (col <- 0 until 32) {
        val expIdx = row * 32 + col
        val outIdx = row * 34 + col + 2
        if (outputElems(outIdx) != expectedHex(expIdx)) {
          if (mismatch < 10) {
            println(f"  mismatch[row=$row%2d col=$col%2d]: out[$outIdx]=0x${outputElems(outIdx) & 0xFFFF}%04x, expected[$expIdx]=0x${expectedHex(expIdx) & 0xFFFF}%04x")
          }
          mismatch += 1
        }
      }
    }
    val totalCompared = 32 * 32
    println(s"  mismatches: $mismatch / $totalCompared")
    mismatch
  }

  "ConvAccelTop" - {

    "TC-TOP-01: identity kernel end-to-end" in {
      simulate(new ConvAccelTopTestHarness) { dut =>
        // ── 初始化 ──
        dut.io.start.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.io.readback.valid.poke(false.B)
        dut.io.readbackResp.ready.poke(false.B)
        dut.io.kernelAddr.poke(0.U)
        dut.io.inputAddr.poke(0.U)
        dut.io.outputAddr.poke(0.U)
        dut.clock.step()

        // ── 读 hex 文件 ──
        val kernelHex   = readHex(resPath + "identity_kernel_kernel.hex")   // 25 values
        val inputHex    = readHex(resPath + "identity_kernel_input.hex")    // 1024 values
        val expectedHex = readHex(resPath + "identity_kernel_expected.hex") // 1024 values

        // ── 预填 kernel @ 0x1000 (8 words = 64 bytes, padded from 25 values) ──
        val kernelWords = packWords(kernelHex, 8)
        prefillMemory(dut, 0x1000L, kernelWords)

        // ── 预填 input @ 0x2000 (256 words = 2048 bytes) ──
        val inputWords = packWords(inputHex, 256)
        prefillMemory(dut, 0x2000L, inputWords)

        // ── 设置地址并启动 ──
        dut.io.kernelAddr.poke(0x1000.U(64.W))
        dut.io.inputAddr.poke(0x2000.U(64.W))
        dut.io.outputAddr.poke(0x3000.U(64.W))
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // ── 主循环：等 done ──
        var prevState = -1
        val stateNames = Array("sIdle","sLoadK","sLoadI","sComp ","sDone ")

        println(s"\n" + "=" * 72)
        println(s"  ConvAccelTop identity kernel end-to-end")
        println(s"  kernel @ 0x1000, input @ 0x2000, output → 0x3000")
        println(s"=" * 72)
        println(f"${"cyc"}%5s | ${"FSM"}%8s | ${"results"}%10s | ${"q"}%5s | ${"dma"}%13s | notes")
        println(f"${"----"}%5s-+-${"------"}%8s-+-${"--------"}%10s-+-${"--"}%5s-+-${"----------"}%13s-|------")

        var cycle  = 0
        var done   = false
        val watchdog = 3000

        while (!done && cycle < watchdog) {
          val st = dut.io.state.peek().litValue.toInt
          val sn = stateNames(st)
          val transition = if (st != prevState) " <<< " + sn else ""
          prevState = st

          val rcnt = dut.io.dbgResultCnt.peek().litValue.toInt
          val qcnt = dut.io.dbgQueueCnt.peek().litValue.toInt
          val dmaDone = dut.io.dbgDmaDone.peek().litToBoolean
          val dmaSt   = dut.io.dbgDmaState.peek().litValue.toInt

          val dmaStateNames = Array("sIdle","sIssue","sWaitR","sUnpck","sGathr","sDone ","sError","sLoadA")
          val dmaSn = dmaStateNames(dmaSt)

          if (transition.nonEmpty || rcnt >= 1024 || cycle % 200 == 0 || cycle < 10) {
            println(f"$cycle%5d | $sn%8s | r=$rcnt%8d | q=$qcnt%3d | dma=$dmaSn%8s done=$dmaDone%5b |$transition")
          }

          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        println(f"${"----"}%5s-+-${"------"}%8s-+-${"------"}%8s-+-${"---"}%5s-|------")
        println(s"  Total: $cycle cycles")

        // ── 验证 ──
        assert(cycle < watchdog, s"watchdog expired at cycle $cycle")
        assert(done, "done should be asserted")

        // ── 读回 output @ 0x3000 (272 words = 1088 elems, 34/row) ──
        val outputWords = readbackMemory(dut, 0x3000L, 272)
        val outputElems = unpackWords(outputWords, 1088)

        val mismatch = verifyOutput(outputElems, expectedHex)
        assert(mismatch == 0, s"$mismatch mismatches")
        assert(cycle < 2500, s"cycle count $cycle exceeds 2500 target")

        println(s"--- SUCCESS: TC-TOP-01 completed in $cycle cycles ---")
      }
    }

    "TC-TOP-02: box blur end-to-end" in {
      simulate(new ConvAccelTopTestHarness) { dut =>
        // ── 初始化 ──
        dut.io.start.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.io.readback.valid.poke(false.B)
        dut.io.readbackResp.ready.poke(false.B)
        dut.io.kernelAddr.poke(0.U)
        dut.io.inputAddr.poke(0.U)
        dut.io.outputAddr.poke(0.U)
        dut.clock.step()

        // ── 读 hex 文件 ──
        val kernelHex   = readHex(resPath + "box_blur_ones_kernel.hex")   // 25 values
        val inputHex    = readHex(resPath + "box_blur_ones_input.hex")    // 1024 values
        val expectedHex = readHex(resPath + "box_blur_ones_expected.hex") // 1024 values

        // ── 预填 kernel @ 0x1000 ──
        val kernelWords = packWords(kernelHex, 8)
        prefillMemory(dut, 0x1000L, kernelWords)

        // ── 预填 input @ 0x2000 ──
        val inputWords = packWords(inputHex, 256)
        prefillMemory(dut, 0x2000L, inputWords)

        // ── 设置地址并启动 ──
        dut.io.kernelAddr.poke(0x1000.U(64.W))
        dut.io.inputAddr.poke(0x2000.U(64.W))
        dut.io.outputAddr.poke(0x3000.U(64.W))
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        println(s"\n" + "=" * 72)
        println(s"  ConvAccelTop box blur end-to-end")
        println(s"  kernel @ 0x1000, input @ 0x2000, output → 0x3000")
        println(s"=" * 72)

        val cycle = runUntilDone(dut, watchdog = 4000)
        println(s"  Total: $cycle cycles")

        assert(cycle < 4000, s"watchdog expired at cycle $cycle")
        assert(cycle < 3000, s"cycle count $cycle exceeds 3000 target")

        // ── 读回 output ──
        val outputWords = readbackMemory(dut, 0x3000L, 272)
        val outputElems = unpackWords(outputWords, 1088)

        val mismatch = verifyOutput(outputElems, expectedHex)
        assert(mismatch == 0, s"$mismatch mismatches")

        println(s"--- SUCCESS: TC-TOP-02 completed in $cycle cycles ---")
      }
    }

    "TC-TOP-03: back-to-back identity kernel" in {
      simulate(new ConvAccelTopTestHarness) { dut =>
        // ── 初始化 ──
        dut.io.start.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.io.readback.valid.poke(false.B)
        dut.io.readbackResp.ready.poke(false.B)
        dut.io.kernelAddr.poke(0.U)
        dut.io.inputAddr.poke(0.U)
        dut.io.outputAddr.poke(0.U)
        dut.clock.step()

        // ── 读 hex 文件 ──
        val kernelHex   = readHex(resPath + "identity_kernel_kernel.hex")
        val inputHex    = readHex(resPath + "identity_kernel_input.hex")
        val expectedHex = readHex(resPath + "identity_kernel_expected.hex")

        // ── 预填 kernel @ 0x1000, input @ 0x2000 ──
        val kernelWords = packWords(kernelHex, 8)
        prefillMemory(dut, 0x1000L, kernelWords)
        val inputWords = packWords(inputHex, 256)
        prefillMemory(dut, 0x2000L, inputWords)

        println(s"\n" + "=" * 72)
        println(s"  ConvAccelTop back-to-back identity kernel")
        println(s"=" * 72)

        // ── 第 1 次运行: output → 0x3000 ──
        println(s"\n  --- Run 1 ---")
        dut.io.kernelAddr.poke(0x1000.U(64.W))
        dut.io.inputAddr.poke(0x2000.U(64.W))
        dut.io.outputAddr.poke(0x3000.U(64.W))
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        val cycle1 = runUntilDone(dut, watchdog = 4000)
        println(s"  Run 1: $cycle1 cycles")

        assert(cycle1 < 4000, s"run 1 watchdog expired at $cycle1")
        assert(cycle1 < 2500, s"run 1 cycle count $cycle1 exceeds 2500 target")

        val out1 = readbackMemory(dut, 0x3000L, 272)
        val elems1 = unpackWords(out1, 1088)
        val mismatch1 = verifyOutput(elems1, expectedHex)
        assert(mismatch1 == 0, s"run 1: $mismatch1 mismatches")

        // ── 第 2 次运行: output → 0x4000 ──
        println(s"\n  --- Run 2 ---")
        dut.io.kernelAddr.poke(0x1000.U(64.W))
        dut.io.inputAddr.poke(0x2000.U(64.W))
        dut.io.outputAddr.poke(0x4000.U(64.W))
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        val cycle2 = runUntilDone(dut, watchdog = 4000)
        println(s"  Run 2: $cycle2 cycles")

        assert(cycle2 < 4000, s"run 2 watchdog expired at $cycle2")
        assert(cycle2 < 2500, s"run 2 cycle count $cycle2 exceeds 2500 target")

        val out2 = readbackMemory(dut, 0x4000L, 272)
        val elems2 = unpackWords(out2, 1088)
        val mismatch2 = verifyOutput(elems2, expectedHex)
        assert(mismatch2 == 0, s"run 2: $mismatch2 mismatches")

        val totalCycles = cycle1 + cycle2
        println(s"\n  Run1: $cycle1  Run2: $cycle2  Total: $totalCycles cycles")
        assert(totalCycles < 5000, s"total cycle count $totalCycles exceeds 5000 target")

        println(s"--- SUCCESS: TC-TOP-03 completed in $totalCycles total cycles ---")
      }
    }
  }
}
