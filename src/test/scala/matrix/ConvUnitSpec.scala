package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ConvUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "ConvUnit should calculate correctly" in {
    simulate(new ConvUnit) { dut =>
      // 初始化为 0
      for (i <- 0 until 25) {
        dut.io.pixels(i).poke(0.S)
        dut.io.weights(i).poke(0.S)
      }
      dut.clock.step()

      // 测试: 1.0 * 1.0 = 1.0 (Q8.8 中 1.0 是 256)
      dut.io.pixels(0).poke(256.S)
      dut.io.weights(0).poke(256.S)
      dut.clock.step()
      dut.io.result.expect(256.S)
      
      println("--- SUCCESS: ConvUnit 数学计算测试通过! ---")
    }
  }
}

