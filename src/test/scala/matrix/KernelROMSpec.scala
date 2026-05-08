package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class KernelROMSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "KernelROM" - {

    "写 25 个值，验证 row-major 地址映射到 5×5" in {
      simulate(new KernelROM) { dut =>
        // 25 个位置写入不同值: reg(n) = n (方便验证映射关系)
        for (addr <- 0 until 25) {
          dut.io.we.poke(true.B)
          dut.io.wAddr.poke(addr.U)
          dut.io.wData.poke(addr.S)        // 地址 0 存 0, 地址 1 存 1, ...
          dut.clock.step()
        }
        dut.io.we.poke(false.B)

        // 验证 kernel(row)(col) = row * 5 + col
        for (r <- 0 until 5; c <- 0 until 5) {
          val expected = (r * 5 + c).S
          dut.io.kernel(r)(c).expect(expected)
        }

        println("--- SUCCESS: KernelROM 地址映射正确 ---")
      }
    }

    "部分写入后未写地址保持原值（零）" in {
      simulate(new KernelROM) { dut =>
        // 只写地址 12（中心位置），其余不动
        dut.io.we.poke(true.B)
        dut.io.wAddr.poke(12.U)
        dut.io.wData.poke(256.S)  // 1.0 in Q8.8
        dut.clock.step()
        dut.io.we.poke(false.B)

        // 中心应该是 256，其余保持 0（RegInit 默认值）
        for (r <- 0 until 5; c <- 0 until 5) {
          if (r == 2 && c == 2) {
            dut.io.kernel(r)(c).expect(256.S)
          } else {
            dut.io.kernel(r)(c).expect(0.S)
          }
        }

        println("--- SUCCESS: 部分写入，其余保持零 ---")
      }
    }
  }
}
