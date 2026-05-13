package matrix

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
// VCD: Chisel 7.7.0 默认用 svsim，需装 Verilator 并在编译时加 trace 支持。
// 当前用逐周期文本追踪替代。
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
 * ConvDMATestHarness — 测试包装器，连接 ConvDMA + FakeScratchpadMemory。
 *
 * 提供 prefill 端口用于预填内存数据。
 * 提供 readCount 输出用于验证读请求次数。
 */
class ConvDMATestHarness extends Module {
  val dma = Module(new ConvDMA)
  val mem = Module(new FakeScratchpadMemory(4096))

  val io = IO(new Bundle {
    val cmd         = Flipped(Decoupled(new DmaCmd))
    val loadStream  = Decoupled(UInt(16.W))
    val storeStream = Flipped(Decoupled(UInt(16.W)))
    val busy        = Output(Bool())
    val done        = Output(Bool())
    val error       = Output(Bool())
    val forceStall  = Input(Bool())
    val prefill     = Flipped(Decoupled(new SimpleMemReq))
    val readCount   = Output(UInt(16.W))
    val fsmState    = Output(UInt(3.W))
    val dbgInflight = Output(UInt(3.W))   // DMA inflightCount
    val dbgFifoCnt  = Output(UInt(4.W))   // DMA respFifo count
    // 调试：暴露 mem 总线信号供逐周期追踪
    val dbgReqV     = Output(Bool())
    val dbgReqR     = Output(Bool())
    val dbgReqAddr  = Output(UInt(64.W))
    val dbgReqData  = Output(UInt(64.W))
    val dbgReqIsWr  = Output(Bool())
    val dbgRespV    = Output(Bool())
    val dbgRespR    = Output(Bool())
    val dbgRespData = Output(UInt(64.W))
  })

  io.cmd <> dma.io.cmd
  io.loadStream <> dma.io.loadStream
  io.storeStream <> dma.io.storeStream
  io.busy := dma.io.busy
  io.done := dma.io.done
  io.error := dma.io.error
  io.fsmState     := dma.io.state
  io.dbgInflight  := dma.io.dbgInflight
  io.dbgFifoCnt   := dma.io.dbgFifoCount
  io.dbgReqV      := dma.io.mem.req.valid
  io.dbgReqR      := dma.io.mem.req.ready
  io.dbgReqAddr   := dma.io.mem.req.bits.addr
  io.dbgReqData   := dma.io.mem.req.bits.data
  io.dbgReqIsWr   := dma.io.mem.req.bits.isWrite
  io.dbgRespV     := dma.io.mem.resp.valid
  io.dbgRespR     := dma.io.mem.resp.ready
  io.dbgRespData  := dma.io.mem.resp.bits.data
  mem.io.forceStall := io.forceStall

  // 预填复用器：prefill.valid 为高时，测试台直连内存，DMA 被阻断
  val doPrefill = io.prefill.valid
  mem.io.mem.req.valid := Mux(doPrefill, io.prefill.valid, dma.io.mem.req.valid)
  mem.io.mem.req.bits   := Mux(doPrefill, io.prefill.bits,   dma.io.mem.req.bits)
  io.prefill.ready      := Mux(doPrefill, mem.io.mem.req.ready, false.B)
  dma.io.mem.req.ready  := Mux(doPrefill, false.B,           mem.io.mem.req.ready)

  dma.io.mem.resp <> mem.io.mem.resp

  // 读请求计数器
  val readReqCounter = RegInit(0.U(16.W))
  when(dma.io.mem.req.fire && !dma.io.mem.req.bits.isWrite) {
    readReqCounter := readReqCounter + 1.U
  }
  io.readCount := readReqCounter
}


/**
 * ConvDMASpec — Phase 3 Step 7: ConvDMA load 路径测试。
 *
 * 验证：
 *   - 256 次 64-bit 读请求
 *   - 1024 个 16-bit 元素按小端序正确拆解
 *   - done 信号正常拉高
 *   - 无错误
 */
class ConvDMASpec extends AnyFreeSpec with Matchers with ChiselSim {

  // 生成 256 个 64-bit word：word i 含 4 个元素 [4i, 4i+1, 4i+2, 4i+3]（小端序）
  def makeInputData(): Array[Long] = {
    Array.tabulate(256) { i =>
      val base = i * 4
      (base & 0xFFFF).toLong |
        (((base + 1) & 0xFFFF).toLong << 16) |
        (((base + 2) & 0xFFFF).toLong << 32) |
        (((base + 3) & 0xFFFF).toLong << 48)
    }
  }

  // 通过 prefill 端口将数据写入 FakeScratchpadMemory
  def prefillMemory(dut: ConvDMATestHarness, baseAddr: Long, data: Array[Long]): Unit = {
    for (i <- data.indices) {
      dut.io.prefill.valid.poke(true.B)
      dut.io.prefill.bits.addr.poke((baseAddr + i * 8).U(64.W))
      dut.io.prefill.bits.data.poke(data(i).U(64.W))
      dut.io.prefill.bits.isWrite.poke(true.B)
      dut.io.prefill.bits.tag.poke(0.U(4.W))
      dut.io.prefill.bits.mask.poke(0xFF.U(8.W))

      // 等 memory ready 后握手
      dut.io.prefill.ready.expect(true.B)
      dut.clock.step()
    }
    dut.io.prefill.valid.poke(false.B)
  }

  "ConvDMA load_input should" - {

    "TC-LOAD-01: load 1024 elements in correct order from 256 words" in {
      simulate(new ConvDMATestHarness) { dut =>
        // ── 初始化 ──
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // ── 预填内存 ──
        val data = makeInputData()
        prefillMemory(dut, 0x1000L, data)

        // 验证预填后计数器仍为 0
        dut.io.readCount.expect(0.U)

        // ── 发送 load_input 命令 ──
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(2048.U(16.W))
        dut.io.cmd.ready.expect(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // ── 主循环：消费 loadStream，等待 done，同时逐周期追踪 FSM ──
        var elems     = Seq.empty[Int]
        var done      = false
        var error     = false
        var cycle     = 0
        var reqCount  = 0
        var wordIdx   = 0
        var prevState  = -1
        var skipped    = false
        val watchdog   = 3000

        val stateNames = Array("sIdle","sIssue","sWaitResp","sUnpack","sGather","sDone ","sError","sLoadA")

        println(s"\n" + "=" * 72)
        println(s"  ConvDMA load_input —— 逐周期硬件追踪")
        println(s"  1024 个 16-bit 元素，256 次 64-bit 读，目标地址 0x1000")
        println(s"=" * 72)
        println(f"${"cyc"}%4s | ${"FSM state"}%10s | ${"mem.req"}%27s | ${"mem.resp"}%27s | ${"loadStream"}%25s |")
        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"---------------------------"}%27s-+-${"---------------------------"}%27s-+-${"-------------------------"}%25s-|")

        while (!done && !error && cycle < watchdog) {
          val state = dut.io.fsmState.peek().litValue.toInt
          val sn    = stateNames(state)
          val transition = if (state != prevState) " <<< " + sn else ""
          prevState = state

          // —— 构建本周期事件描述 ——
          var evReq   = ""
          var evResp  = ""
          var evLoad  = ""

          // mem.req
          val reqV = dut.io.dbgReqV.peek().litToBoolean
          val reqR = dut.io.dbgReqR.peek().litToBoolean
          if (reqV && reqR) {
            reqCount += 1
            val addr = dut.io.dbgReqAddr.peek().litValue
            evReq = f"fire(rd,addr=0x$addr%04x,w$reqCount%3d)"
            wordIdx = ((addr - 0x1000) / 8).toInt
          } else if (reqV) {
            evReq = "valid=1(stall)"
          }

          // mem.resp
          val respV = dut.io.dbgRespV.peek().litToBoolean
          val respR = dut.io.dbgRespR.peek().litToBoolean
          if (respV && respR) {
            evResp = f"fire(w$wordIdx%3d,data=...)"

          } else if (respV) {
            evResp = "valid=1(wait)"
          }

          // loadStream
          val hasData = dut.io.loadStream.valid.peek().litToBoolean
          dut.io.loadStream.ready.poke(hasData.B)
          if (hasData) {
            val elem   = dut.io.loadStream.bits.peek().litValue.toInt
            val expect = elems.length
            val ok     = if (elem == expect) "OK" else s"FAIL(exp $expect)"
            evLoad = f"deq elem[${elems.length}%4d]=$elem%4d $ok"
            elems = elems :+ elem
          }

          // 只打印前 2 个 word + 最后 1 个 word
          val show = (wordIdx < 2) || (wordIdx >= 255)

          if (show) {
            if (!skipped && wordIdx >= 2) {
              skipped = true
              println(f"  ...  | ${""}%10s | ${""}%27s | ${""}%27s | ${"(word 3~254: 252 words × 6 cycles = 1512 cycles)"}%25s |")
              println(f"  ...  | ${""}%10s | ${""}%27s | ${""}%27s | ${"(pattern: sIssue→sWaitResp→sUnpack(×4))"}%25s |")
            }
            println(f"$cycle%4d | $sn%10s | $evReq%27s | $evResp%27s | $evLoad%25s |$transition")
          }

          done  = dut.io.done.peek().litToBoolean
          error = dut.io.error.peek().litToBoolean

          dut.clock.step()
          cycle += 1
        }

        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"---------------------------"}%27s-+-${"---------------------------"}%27s-+-${"-------------------------"}%25s-+")
        println(s"  Total: $cycle cycles, $reqCount read requests, ${elems.length} elements consumed")

        // ── 验证 ──
        assert(!error, "DMA should not report error")
        assert(cycle < watchdog, s"Watchdog expired at cycle $cycle")
        assert(elems.length == 1024, s"Expected 1024 elements, got ${elems.length}")
        assert(elems == (0 until 1024).toSeq, "Elements out of order or wrong values")
        dut.io.readCount.expect(256.U)
        dut.io.done.expect(true.B)

        println(s"--- SUCCESS: TC-LOAD-01 completed in $cycle cycles, ${elems.length} elements ---")
      }
    }
  }

  "ConvDMA load_kernel should" - {

    "TC-LOAD-02: load 32 elements (8 words, kernel padded to 64 bytes)" in {
      simulate(new ConvDMATestHarness) { dut =>
        // ── 初始化 ──
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // ── 生成 kernel 数据：8 word × 4 elem = 32 elem, 值 0..31 ──
        def makeKernelData(): Array[Long] = {
          Array.tabulate(8) { i =>
            val base = i * 4
            (base & 0xFFFF).toLong |
              (((base + 1) & 0xFFFF).toLong << 16) |
              (((base + 2) & 0xFFFF).toLong << 32) |
              (((base + 3) & 0xFFFF).toLong << 48)
          }
        }

        // ── 预填内存 ──
        prefillMemory(dut, 0x2000L, makeKernelData())
        dut.io.readCount.expect(0.U)

        // ── 发送 load_kernel 命令（2 字节对齐，64 字节） ──
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_kernel)
        dut.io.cmd.bits.baseAddr.poke(0x2000.U(64.W))
        dut.io.cmd.bits.length.poke(64.U(16.W))
        dut.io.cmd.ready.expect(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // ── 主循环 ──
        var elems    = Seq.empty[Int]
        var done     = false
        var error    = false
        var cycle    = 0
        var reqCount = 0
        var wordIdx  = 0
        var prevState = -1
        val stateNames = Array("sIdle","sIssue","sWaitResp","sUnpack","sGather","sDone ","sError","sLoadA")

        println(s"\n" + "=" * 72)
        println(s"  ConvDMA load_kernel —— 逐周期硬件追踪")
        println(s"  32 个 16-bit 元素，8 次 64-bit 读，目标地址 0x2000")
        println(s"=" * 72)
        println(f"${"cyc"}%4s | ${"FSM state"}%10s | ${"mem.req"}%27s | ${"mem.resp"}%27s | ${"loadStream"}%25s |")
        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"---------------------------"}%27s-+-${"---------------------------"}%27s-+-${"-------------------------"}%25s-|")

        while (!done && !error && cycle < 500) {
          val state = dut.io.fsmState.peek().litValue.toInt
          val sn    = stateNames(state)
          val transition = if (state != prevState) " <<< " + sn else ""
          prevState = state

          var evReq  = ""
          var evResp = ""
          var evLoad = ""

          val reqV = dut.io.dbgReqV.peek().litToBoolean
          val reqR = dut.io.dbgReqR.peek().litToBoolean
          if (reqV && reqR) {
            reqCount += 1
            val addr = dut.io.dbgReqAddr.peek().litValue
            evReq = f"fire(rd,addr=0x$addr%04x,w$reqCount)"
            wordIdx = ((addr - 0x2000) / 8).toInt
          }

          val respV = dut.io.dbgRespV.peek().litToBoolean
          val respR = dut.io.dbgRespR.peek().litToBoolean
          if (respV && respR) {
            evResp = f"fire(w$wordIdx,data=...)"
          }

          val hasData = dut.io.loadStream.valid.peek().litToBoolean
          dut.io.loadStream.ready.poke(hasData.B)
          if (hasData) {
            val elem   = dut.io.loadStream.bits.peek().litValue.toInt
            val expect = elems.length
            val ok     = if (elem == expect) "OK" else s"FAIL(exp $expect)"
            evLoad = f"deq elem[${elems.length}%4d]=$elem%4d $ok"
            elems = elems :+ elem
          }

          println(f"$cycle%4d | $sn%10s | $evReq%27s | $evResp%27s | $evLoad%25s |$transition")

          done  = dut.io.done.peek().litToBoolean
          error = dut.io.error.peek().litToBoolean

          dut.clock.step()
          cycle += 1
        }

        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"---------------------------"}%27s-+-${"---------------------------"}%27s-+-${"-------------------------"}%25s-+")
        println(s"  Total: $cycle cycles, $reqCount read requests, ${elems.length} elements consumed")

        // ── 验证 ──
        assert(!error, "DMA should not report error")
        assert(cycle < 500, s"Watchdog expired at cycle $cycle")
        assert(elems.length == 32, s"Expected 32 elements, got ${elems.length}")
        assert(elems == (0 until 32).toSeq, "Elements out of order or wrong values")
        dut.io.readCount.expect(8.U)
        dut.io.done.expect(true.B)

        println(s"--- SUCCESS: TC-LOAD-02 completed in $cycle cycles, ${elems.length} elements ---")
      }
    }
  }

  "ConvDMA store_output should" - {

    "TC-STORE-01: store 1024 elements in 256 writes" in {
      simulate(new ConvDMATestHarness) { dut =>
        // ── 初始化 ──
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // ── 发送 store_output 命令 ──
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.store_output)
        dut.io.cmd.bits.baseAddr.poke(0x3000.U(64.W))
        dut.io.cmd.bits.length.poke(2048.U(16.W))
        dut.io.cmd.ready.expect(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // ── 主循环：喂 storeStream，追踪写请求 ──
        var elemIdx   = 0
        var writeCnt  = 0
        var writes    = Seq.empty[(Long, Long)]  // (addr, data)
        var done      = false
        var error     = false
        var cycle     = 0
        var prevState = -1
        val totalElem = 1024
        val stateNames = Array("sIdle","sIssue","sWaitResp","sUnpack","sGather","sDone ","sError","sLoadA")

        println(s"\n" + "=" * 72)
        println(s"  ConvDMA store_output —— 逐周期硬件追踪")
        println(s"  喂入 1024 个 16-bit 元素，256 次 64-bit 写，目标地址 0x3000")
        println(s"=" * 72)
        println(f"${"cyc"}%4s | ${"FSM state"}%10s | ${"storeStream"}%20s | ${"mem.req(write)"}%30s |")
        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"--------------------"}%20s-+-${"------------------------------"}%30s-|")

        while (!done && !error && cycle < 3000) {
          val state = dut.io.fsmState.peek().litValue.toInt
          val sn    = stateNames(state)
          val transition = if (state != prevState) " <<< " + sn else ""
          prevState = state

          var evFeed  = ""
          var evWrite = ""

          // 喂元素到 storeStream
          dut.io.storeStream.valid.poke(false.B)
          if (elemIdx < totalElem) {
            val ready = dut.io.storeStream.ready.peek().litToBoolean
            if (ready) {
              dut.io.storeStream.valid.poke(true.B)
              dut.io.storeStream.bits.poke(elemIdx.U(16.W))
              evFeed = f"enq elem[$elemIdx%4d]=$elemIdx%4d"
              // fire 发生在 valid && ready 都为 1 的当拍
              elemIdx += 1
            }
          }

          // 追踪写请求
          val reqV = dut.io.dbgReqV.peek().litToBoolean
          val reqR = dut.io.dbgReqR.peek().litToBoolean
          val reqW = dut.io.dbgReqIsWr.peek().litToBoolean
          if (reqV && reqR && reqW) {
            writeCnt += 1
            val addr = dut.io.dbgReqAddr.peek().litValue.toLong
            val data = dut.io.dbgReqData.peek().litValue.toLong
            writes = writes :+ ((addr, data))
            evWrite = f"fire(addr=0x$addr%04x,data=0x$data%016x)"
          }

          done  = dut.io.done.peek().litToBoolean
          error = dut.io.error.peek().litToBoolean

          // 打印前 2 组 + 最后 1 组
          val group = writeCnt
          val show = (group < 3) || (group >= 255) || transition != "" || done

          if (show) {
            if (!done && group == 3 && evWrite != "") {
              println(f"  ...  | ${""}%10s | ${""}%20s | ${"(groups 4~255: 252 groups × 5 cycles hidden)"}%30s |")
            }
            println(f"$cycle%4d | $sn%10s | $evFeed%20s | $evWrite%30s |$transition")
          }

          dut.clock.step()
          cycle += 1
        }

        println(f"${"----"}%4s-+-${"----------"}%10s-+-${"--------------------"}%20s-+-${"------------------------------"}%30s-+")
        println(s"  Total: $cycle cycles, $writeCnt writes, $elemIdx elements fed")

        // ── 验证 ──
        assert(!error, "DMA should not report error")
        assert(cycle < 3000, s"Watchdog expired at cycle $cycle")
        assert(writeCnt == 256, s"Expected 256 writes, got $writeCnt")
        assert(elemIdx == 1024, s"Expected 1024 elements fed, got $elemIdx")

        // 验证每次写的数据：4 个 16-bit 元素按小端序打包
        for (i <- 0 until 256) {
          val (addr, data) = writes(i)
          val expectedAddr = 0x3000L + i * 8
          val base = i * 4
          val expectedData = (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
          assert(addr == expectedAddr,
            f"Write $i: addr=0x$addr%04x, expected 0x$expectedAddr%04x")
          assert(data == expectedData,
            f"Write $i: data=0x$data%016x, expected 0x$expectedData%016x")
        }

        dut.io.done.expect(true.B)

        println(s"--- SUCCESS: TC-STORE-01 completed in $cycle cycles, $writeCnt writes, $elemIdx elements ---")
      }
    }
  }

  "ConvDMA alignment check should" - {

    def runAlignTest(op: chisel3.UInt, baseAddr: Long, length: Int, desc: String): Unit = {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // 发送非法命令
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(op)
        dut.io.cmd.bits.baseAddr.poke(baseAddr.U(64.W))
        dut.io.cmd.bits.length.poke(length.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // 等 DMA 跳 sError
        dut.clock.step(2)

        // 验证
        dut.io.error.expect(true.B, s"$desc: error should be 1")
        dut.io.done.expect(true.B, s"$desc: done should be 1")
        dut.io.readCount.expect(0.U, s"$desc: no reads should happen")
        dut.io.busy.expect(false.B, s"$desc: busy should be 0")

        println(s"--- SUCCESS: $desc ---")
      }
    }

    "TC-ALIGN-01: load_input with address not 8-byte aligned" in {
      runAlignTest(DmaOp.load_input, 0x1001L, 2048,
        "load_input addr=0x1001 (misaligned)")
    }

    "TC-ALIGN-02: load_kernel with address not 2-byte aligned" in {
      runAlignTest(DmaOp.load_kernel, 0x2001L, 64,
        "load_kernel addr=0x2001 (misaligned)")
    }

    "TC-ALIGN-03: store_output with address not 8-byte aligned" in {
      runAlignTest(DmaOp.store_output, 0x3001L, 2048,
        "store_output addr=0x3001 (misaligned)")
    }

    "TC-ALIGN-04: address is zero" in {
      runAlignTest(DmaOp.load_input, 0x0L, 2048,
        "load_input addr=0 (null)")
    }
  }

  "ConvDMA backpressure should" - {

    "TC-BP-01: mem stall and resume (forceStall)" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(true.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // 预填
        val data = Array.tabulate(256) { i =>
          val base = i * 4
          (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
        }
        for (i <- data.indices) {
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data(i).U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // 发 load_input
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(2048.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // 统一 while 循环：先跑 3 个 word → stall 20 拍 → 释放
        var elems   = Seq.empty[Int]
        var cycle   = 0
        var done    = false
        var stalled = false

        while (!done && cycle < 3000) {
          // 第 18 拍拉 stall，第 38 拍释放
          if (cycle == 18)      dut.io.forceStall.poke(true.B)
          if (cycle == 38)      dut.io.forceStall.poke(false.B)

          if (dut.io.loadStream.valid.peek().litToBoolean) {
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          }

          // stall 期间验证 done 没拉高
          if (cycle == 37) {
            val sd = dut.io.done.peek().litToBoolean
            assert(!sd, "DMA should not finish while stalled")
          }

          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        assert(elems.length == 1024, s"Expected 1024, got ${elems.length}")
        assert(elems == (0 until 1024).toSeq, "Elements out of order after stall")
        dut.io.done.expect(true.B)
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-BP-01 mem stall/release, ${elems.length} elements ---")
      }
    }

    "TC-BP-02: loadStream backpressure" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // 预填
        val data = Array.tabulate(256) { i =>
          val base = i * 4
          (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
        }
        for (i <- data.indices) {
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data(i).U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // 发 load_input
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(2048.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // 消费前 8 个元素，然后拉低 ready（模拟下游忙）
        var elems = Seq.empty[Int]
        var cycle = 0
        var done  = false

        while (elems.length < 8 && cycle < 100) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            dut.io.loadStream.ready.poke(true.B)
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          dut.clock.step()
          cycle += 1
        }
        // 拉低 ready — Queue 会填满，DMA 卡在 sUnpack
        dut.io.loadStream.ready.poke(false.B)

        // 等 30 cycles — 验证没死锁
        dut.clock.step(30)
        val busyWhileStalled = dut.io.busy.peek().litToBoolean
        assert(busyWhileStalled, "DMA should be busy (stalled in sUnpack)")

        // 恢复消费
        var done2 = false
        while (!done2 && cycle < 3000) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            dut.io.loadStream.ready.poke(true.B)
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          } else {
            dut.io.loadStream.ready.poke(false.B)
          }
          done2 = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        assert(elems.length == 1024, s"Expected 1024, got ${elems.length}")
        assert(elems == (0 until 1024).toSeq, "Elements out of order after backpressure")
        dut.io.done.expect(true.B)
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-BP-02 loadStream backpressure, ${elems.length} elements ---")
      }
    }

    "TC-BP-03: storeStream backpressure (upstream pauses)" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // 发 store_output
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.store_output)
        dut.io.cmd.bits.baseAddr.poke(0x3000.U(64.W))
        dut.io.cmd.bits.length.poke(2048.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // 喂 20 个元素（5 组写），然后暂停
        var elemIdx = 0
        var cycle   = 0

        while (elemIdx < 20 && cycle < 100) {
          dut.io.storeStream.valid.poke(false.B)
          if (dut.io.storeStream.ready.peek().litToBoolean) {
            dut.io.storeStream.valid.poke(true.B)
            dut.io.storeStream.bits.poke(elemIdx.U(16.W))
            elemIdx += 1
          }
          dut.clock.step()
          cycle += 1
        }

        // 断流 20 cycles — DMA 等元素，不报错
        dut.io.storeStream.valid.poke(false.B)
        dut.clock.step(20)
        val notDone = !dut.io.done.peek().litToBoolean
        assert(notDone, "DMA should not finish while waiting for elements")

        // 恢复喂入剩余元素
        var done = false
        while (!done && elemIdx < 1024 && cycle < 3000) {
          dut.io.storeStream.valid.poke(false.B)
          if (dut.io.storeStream.ready.peek().litToBoolean) {
            dut.io.storeStream.valid.poke(true.B)
            dut.io.storeStream.bits.poke(elemIdx.U(16.W))
            elemIdx += 1
          }
          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        // 等 done
        while (!done && cycle < 3000) {
          dut.io.storeStream.valid.poke(false.B)
          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        assert(elemIdx == 1024, s"Expected 1024 elements fed, got $elemIdx")
        dut.io.done.expect(true.B)
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-BP-03 storeStream backpressure, $elemIdx elements ---")
      }
    }
  }

  "ConvDMA pipeline boundary" - {

    "TC-PIPE-01: inflight window fills to 4 then blocks" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(true.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // Prefill 64 words (more than enough to fill inflight window)
        for (i <- 0 until 64) {
          val base = i * 4
          val data = (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data.U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // Launch load_input: 64 words = 256 elements
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(512.U(16.W))  // 64 words
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        var inflightPeeked = Seq.empty[Int]
        var elems = Seq.empty[Int]
        var cycle = 0
        var done = false

        while (!done && cycle < 1000) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          inflightPeeked = inflightPeeked :+ dut.io.dbgInflight.peek().litValue.toInt
          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        // Verify inflight reached 4
        val maxInflight = inflightPeeked.max
        assert(maxInflight == 4, s"inflight should reach 4, max was $maxInflight")
        // Verify 256 elements correct
        assert(elems.length == 256, s"Expected 256, got ${elems.length}")
        assert(elems == (0 until 256).toSeq, "Elements out of order")
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-PIPE-01 inflight max=$maxInflight, ${elems.length} elements ---")
      }
    }

    "TC-PIPE-02: respFIFO depth stress under backpressure" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(false.B)  // start with backpressure
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // Prefill 64 words
        for (i <- 0 until 64) {
          val base = i * 4
          val data = (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data.U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // Launch load_input: 64 words
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(512.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        // Let DMA issue 4+ requests while loadStream backpressured
        // DMA will fill inflight window, respFIFO accumulates
        var maxFifo = 0
        var cycle = 0
        for (cyc <- 0 until 20) {
          val cnt = dut.io.dbgFifoCnt.peek().litValue.toInt
          if (cnt > maxFifo) maxFifo = cnt
          dut.clock.step()
          cycle += 1
        }

        assert(maxFifo >= 2, s"FIFO should have >=2 entries under BP, got max=$maxFifo")

        // Now release backpressure and consume all
        var elems = Seq.empty[Int]
        var done = false
        while (!done && cycle < 1000) {
          val hasData = dut.io.loadStream.valid.peek().litToBoolean
          dut.io.loadStream.ready.poke(hasData.B)
          if (hasData) {
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          val cnt = dut.io.dbgFifoCnt.peek().litValue.toInt
          if (cnt > maxFifo) maxFifo = cnt
          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        assert(elems.length == 256, s"Expected 256, got ${elems.length}")
        assert(elems == (0 until 256).toSeq, "Elements out of order")
        assert(maxFifo <= 8, s"FIFO count $maxFifo should not exceed depth 8")
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-PIPE-02 FIFO max depth=$maxFifo, ${elems.length} elements ---")
      }
    }

    "TC-PIPE-03: back-to-back transfers (small then large)" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(true.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // Prefill 8 words (kernel) + 256 words (input)
        for (i <- 0 until 264) {
          val base = i * 4
          val data = (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data.U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // --- Transfer 1: kernel load (8 words = 32 elements) ---
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_kernel)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(64.U(16.W))  // 8 words
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        var elems1 = Seq.empty[Int]
        var cycle = 0
        var done1 = false
        while (!done1 && cycle < 500) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            elems1 = elems1 :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          done1 = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }
        dut.io.done.expect(true.B)
        dut.io.error.expect(false.B)
        assert(elems1.length == 32, s"Transfer 1: expected 32, got ${elems1.length}")
        assert(elems1 == (0 until 32).toSeq, "Transfer 1: elements mismatch")

        // Verify state reset: inflight=0, no stale data
        val inflightAfter1 = dut.io.dbgInflight.peek().litValue.toInt
        assert(inflightAfter1 == 0, s"inflight should be 0 after transfer 1, got $inflightAfter1")

        // --- Transfer 2: input load (256 words = 1024 elements) from 0x1040 ---
        // 8 words × 8 bytes = 0x40, so start at offset 0x1040
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1040.U(64.W))  // after first 8 words
        dut.io.cmd.bits.length.poke(2048.U(16.W))       // 256 words
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        var elems2 = Seq.empty[Int]
        var done2 = false
        while (!done2 && cycle < 3000) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            elems2 = elems2 :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          done2 = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }
        dut.io.done.expect(true.B)
        dut.io.error.expect(false.B)
        assert(elems2.length == 1024, s"Transfer 2: expected 1024, got ${elems2.length}")
        // Transfer 1 consumed words 0-7 (elems 0-31), Transfer 2 consumes words 8-263 (elems 32-1055)
        assert(elems2 == (32 until 1056).toSeq, "Transfer 2: elements mismatch")

        println(s"--- SUCCESS: TC-PIPE-03 back-to-back: ${elems1.length} + ${elems2.length} elements ---")
      }
    }

    "TC-PIPE-04: inflightCount net-change correctness" in {
      simulate(new ConvDMATestHarness) { dut =>
        dut.io.cmd.valid.poke(false.B)
        dut.io.loadStream.ready.poke(true.B)
        dut.io.storeStream.valid.poke(false.B)
        dut.io.forceStall.poke(false.B)
        dut.io.prefill.valid.poke(false.B)
        dut.clock.step()

        // Prefill 32 words
        for (i <- 0 until 32) {
          val base = i * 4
          val data = (base & 0xFFFF).toLong |
            (((base + 1) & 0xFFFF).toLong << 16) |
            (((base + 2) & 0xFFFF).toLong << 32) |
            (((base + 3) & 0xFFFF).toLong << 48)
          dut.io.prefill.valid.poke(true.B)
          dut.io.prefill.bits.addr.poke((0x1000L + i * 8).U(64.W))
          dut.io.prefill.bits.data.poke(data.U(64.W))
          dut.io.prefill.bits.isWrite.poke(true.B)
          dut.io.prefill.ready.expect(true.B)
          dut.clock.step()
        }
        dut.io.prefill.valid.poke(false.B)

        // Launch load_input: 32 words
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.op.poke(DmaOp.load_input)
        dut.io.cmd.bits.baseAddr.poke(0x1000.U(64.W))
        dut.io.cmd.bits.length.poke(256.U(16.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)

        var inflightTrace = Seq.empty[Int]
        var elems = Seq.empty[Int]
        var cycle = 0
        var done = false

        while (!done && cycle < 1000) {
          if (dut.io.loadStream.valid.peek().litToBoolean) {
            elems = elems :+ dut.io.loadStream.bits.peek().litValue.toInt
          }
          inflightTrace = inflightTrace :+ dut.io.dbgInflight.peek().litValue.toInt
          done = dut.io.done.peek().litToBoolean
          dut.clock.step()
          cycle += 1
        }

        // Verify inflightCount changes by at most ±1 per cycle
        for (i <- 1 until inflightTrace.length) {
          val delta = inflightTrace(i) - inflightTrace(i - 1)
          assert(delta >= -1 && delta <= 1,
            s"inflight delta at cycle $i is $delta (${inflightTrace(i-1)} → ${inflightTrace(i)})")
        }

        assert(elems.length == 128, s"Expected 128, got ${elems.length}")
        assert(elems == (0 until 128).toSeq, "Elements out of order")
        // Final inflight should be 0
        assert(inflightTrace.last == 0, s"Final inflight should be 0, got ${inflightTrace.last}")
        dut.io.error.expect(false.B)

        println(s"--- SUCCESS: TC-PIPE-04 inflight delta verified over ${inflightTrace.length} cycles ---")
      }
    }
  }
}
