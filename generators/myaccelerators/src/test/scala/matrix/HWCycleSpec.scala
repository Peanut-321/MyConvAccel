package matrix

import chisel3._
import chisel3.iotesters.{PeekPokeTester, Driver}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HWCycleSpec extends AnyFreeSpec with Matchers {

  "ConvEngine 32x32 5x5 SAME cycle count" in {
    val ok = Driver.execute(Array("--backend-name", "treadle"), () => new ConvEngine) { dut =>
      new PeekPokeTester(dut) {

        poke(dut.io.stall, 0)

        // Load identity kernel
        for (addr <- 0 until 25) {
          poke(dut.io.kernelWe, 1)
          poke(dut.io.kernelAddr, addr)
          poke(dut.io.kernelData, if (addr == 12) 256 else 0)
          step(1)
        }
        poke(dut.io.kernelWe, 0)

        // 36x36 padded input
        val padded = Array.fill(36, 36)(0)
        for (r <- 0 until 32; c <- 0 until 32)
          padded(r + 2)(c + 2) = ((r * 32 + c) * 17) & 0xFF

        var colV = 0; var outV = 0
        var fc: Long = -1; var lc: Long = -1
        var fo: Long = -1; var lo: Long = -1

        for (outR <- 0 until 32; pc <- 0 until 41) {
          val inImg = pc < 36; val valid = pc >= 2 && pc < 36
          for (dr <- 0 until 5)
            poke(dut.io.colIn(dr), if (inImg) padded(outR + dr)(pc) else 0)
          poke(dut.io.colValid, if (valid) 1 else 0)
          step(1)

          val now = this.t  // capture current tick

          if (valid) {
            colV += 1
            if (fc < 0) fc = now
            lc = now
          }
          if (peek(dut.io.outValid) == BigInt(1)) {
            outV += 1
            if (fo < 0) fo = now
            lo = now
          }
        }

        // Drain tail
        for (i <- 0 until 10) {
          for (dr <- 0 until 5) poke(dut.io.colIn(dr), 0)
          poke(dut.io.colValid, 0)
          step(1)
          if (peek(dut.io.outValid) == BigInt(1)) {
            outV += 1
            lo = this.t
          }
        }

        val colSpan = lc - fc + 1
        val outSpan = lo - fo + 1
        val pipeLat = fo - fc

        println("")
        println("======================================")
        println(s"  colValid pulses:  $colV")
        println(s"  outValid pulses:  $outV")
        println(s"  colValid span:    $colSpan")
        println(s"  outValid span:    $outSpan")
        println(s"  pipeline latency: $pipeLat")
        println(s"  total steps:      ${this.t}")
        println(s"")
        println(s"  HW_CYCLES=$colSpan")
        println("======================================")

        assert(colV == 1088)
        assert(outV == 1088)
      }
    }
    assert(ok)
  }
}
