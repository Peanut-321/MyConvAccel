package matrix

import chisel3._
import chisel3.iotesters.{PeekPokeTester, Driver}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class FullAccelTester(dut: ConvAccelTop) extends PeekPokeTester(dut) {

  val N = 32
  val input16 = Array.fill(N * N)(0)
  input16(16 * N + 16) = 256  // center pixel = 1.0

  val kernel16 = Array.fill(25)(0)
  kernel16(2 * 5 + 2) = 256    // center identity

  def pack16(arr: Array[Int], base: Int): BigInt = {
    var w: Long = 0
    for (i <- 0 until 4) {
      val idx = base + i
      val v = if (idx < arr.length) arr(idx) & 0xFFFF else 0
      w |= ((v.toLong & 0xFFFF) << (16 * i))
    }
    BigInt(w)
  }

  val inputMem  = Array.tabulate(256)(i => pack16(input16, i * 4))   // 1024 half-words = 256 words
  val kernelMem = Array.tabulate(8)(i => if (i < 4) pack16(kernel16, i * 4) else BigInt(0))

  // Defaults
  poke(dut.io.start, 0)
  poke(dut.io.kernelAddr, 0)
  poke(dut.io.inputAddr, 0)
  poke(dut.io.outputAddr, 0)
  poke(dut.io.mem.req.ready, 1)    // always ready for requests
  poke(dut.io.mem.resp.valid, 0)
  poke(dut.io.mem.resp.bits.data, 0)
  poke(dut.io.mem.resp.bits.tag, 0)

  step(5)

  // SETUP: kernel at 0x1000, input at 0x8000, output at 0x20000 (all non-zero!)
  poke(dut.io.kernelAddr, 0x1000L)
  poke(dut.io.inputAddr,  0x8000L)
  poke(dut.io.outputAddr, 0x20000L)
  poke(dut.io.start, 1)
  step(1)
  poke(dut.io.start, 0)

  val t_start = this.t

  var reqs_served = 0
  var store_words = 0
  var done_seen = false
  var t_done: Long = 0
  var last_top: Long = -1
  var last_dma: Long = -1
  var last_print: Long = t_start
  val max_cycles = 30000

  while (!done_seen && this.t - t_start < max_cycles) {
    // Always accept memory requests
    poke(dut.io.mem.req.ready, 1)

    val req_valid = peek(dut.io.mem.req.valid) == BigInt(1)
    val req_addr  = peek(dut.io.mem.req.bits.addr).toLong
    val req_write = peek(dut.io.mem.req.bits.isWrite) == BigInt(1)

    if (req_valid) {
      if (!req_write) {
        val word_addr = req_addr >> 3
        val data = if (word_addr >= 0x1000 && word_addr < 0x1100) {
          val ioff = (word_addr - 0x1000).toInt
          if (ioff < 256) inputMem(ioff) else BigInt(0)
        } else if (word_addr >= 0x200 && word_addr < 0x400) {
          val koff = (word_addr - 0x200).toInt
          if (koff < 8) kernelMem(koff) else BigInt(0)
        } else { BigInt(0) }
        poke(dut.io.mem.resp.valid, 1)
        poke(dut.io.mem.resp.bits.data, data)
        poke(dut.io.mem.resp.bits.tag, peek(dut.io.mem.req.bits.tag))
        reqs_served += 1
      } else {
        store_words += 1
        poke(dut.io.mem.resp.valid, 1)
        poke(dut.io.mem.resp.bits.data, 0)
        poke(dut.io.mem.resp.bits.tag, peek(dut.io.mem.req.bits.tag))
      }
    } else {
      poke(dut.io.mem.resp.valid, 0)
    }

    step(1)

    val cur_top = peek(dut.io.state).toLong
    val cur_dma = peek(dut.io.dbgDmaState).toLong
    if (cur_top != last_top || cur_dma != last_dma || this.t - last_print > 1000) {
      println(f"[DBG] t=${this.t}%6d top=$cur_top dma=$cur_dma done=${peek(dut.io.done)} resCnt=${peek(dut.io.dbgResultCnt)} qCnt=${peek(dut.io.dbgQueueCnt)} reqs=$reqs_served store=$store_words")
      last_top = cur_top; last_dma = cur_dma; last_print = this.t
    }

    // done = Top FSM done AND DMA done (all stores completed)
    val top_done = peek(dut.io.done) == BigInt(1)
    val dma_done = peek(dut.io.dbgDmaDone) == BigInt(1)
    if (!done_seen && top_done && dma_done) {
      done_seen = true
      t_done = this.t
    }
  }

  val hw_cycles = t_done - t_start + 1

  println("")
  println("==============================================")
  println("  Full ConvAccelTop HW Cycle Count")
  println("==============================================")
  println(s"  Start cycle:        $t_start")
  println(s"  Done cycle:         $t_done")
  println(s"  HW_FULL_ACCEL:      $hw_cycles  cycles")
  println(s"  DMA reads:          $reqs_served")
  println(s"  DMA writes:         $store_words")
  println(s"  State at done:      ${peek(dut.io.state)}")
  println(s"  Result count:       ${peek(dut.io.dbgResultCnt)}")
  println("==============================================")

  assert(done_seen, s"FAIL: did not finish after $max_cycles cycles")
  // After DMA finishes, state may have returned to sIdle(0) — that's fine
}

class FullAccelCycleSpec extends AnyFreeSpec with Matchers {
  "Full ConvAccelTop 32x32 identity kernel cycle count" in {
    val ok = Driver.execute(
      Array("--backend-name", "treadle"),
      () => new ConvAccelTop
    ) { dut => new FullAccelTester(dut) }
    assert(ok)
  }
}
