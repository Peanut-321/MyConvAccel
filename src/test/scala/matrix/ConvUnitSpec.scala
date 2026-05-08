package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ConvUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "ConvUnit 流水线" - {

    "identity kernel: 延迟 5 拍后 outValid 拉高" in {
      simulate(new ConvUnit) { dut =>
        // Identity kernel: 只有中心 = 1.0 (0x0100)
        for (r <- 0 until 5; c <- 0 until 5) {
          val v = if (r == 2 && c == 2) 256 else 0
          dut.io.window(r)(c).poke(v.S)
          dut.io.kernel(r)(c).poke(v.S)
        }

        dut.io.inValid.poke(true.B)

        // 前 4 拍 outValid 应该 false
        for (i <- 1 to 4) {
          dut.clock.step()
          dut.io.outValid.expect(false.B)
        }

        // 第 5 拍 outValid true, result = 256 (1.0 × 1.0)
        dut.clock.step()
        dut.io.outValid.expect(true.B)
        dut.io.result.expect(256.S)

        println("--- SUCCESS: 流水线延迟 ---")
      }
    }

    "饱和: 大正数 → 32767, 大负数 → -32768" in {
      simulate(new ConvUnit) { dut =>
        // Kernel: 中心 = 2.0
        for (r <- 0 until 5; c <- 0 until 5) {
          dut.io.kernel(r)(c).poke(if (r == 2 && c == 2) 512 else 0)
        }

        // 正饱和: window 中心 = 100.0
        for (r <- 0 until 5; c <- 0 until 5) {
          dut.io.window(r)(c).poke(if (r == 2 && c == 2) 25600 else 0)
        }
        dut.io.inValid.poke(true.B)
        for (i <- 1 to 6) { dut.clock.step() }
        dut.io.result.expect(32767.S)

        // 负饱和: window 中心 = -100.0
        for (r <- 0 until 5; c <- 0 until 5) {
          dut.io.window(r)(c).poke(if (r == 2 && c == 2) -25600 else 0)
        }
        dut.io.inValid.poke(true.B)
        for (i <- 1 to 6) { dut.clock.step() }
        dut.io.result.expect(-32768.S)

        println("--- SUCCESS: 饱和 ---")
      }
    }
  }
}
