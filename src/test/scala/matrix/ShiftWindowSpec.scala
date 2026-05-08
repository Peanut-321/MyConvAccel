package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ShiftWindowSpec extends AnyFreeSpec with Matchers with ChiselSim {

  "ShiftWindow" - {

    "复位后 window 全零" in {
      simulate(new ShiftWindow) { dut =>
        dut.clock.step()

        for (r <- 0 until 5; c <- 0 until 5) {
          dut.io.window(r)(c).expect(0.S)
        }
      }
    }

    "连续喂 5 列数据，验证 shift 方向" in {
      simulate(new ShiftWindow) { dut =>
        // 喂 5 拍: 每拍一列 5 个像素。
        // 像素值编码了 (row, feed_index):
        //   row 0 依次收到 0,1,2,3,4
        //   row 1 依次收到 10,11,12,13,14
        //   ...以此类推，方便验证 c0 是最新列、c4 是最旧列

        for (feed <- 0 until 5) {
          for (row <- 0 until 5) {
            dut.io.colIn(row).poke((row * 10 + feed).S)
          }
          dut.io.colValid.poke(true.B)
          dut.clock.step()
        }

        // 5 拍后读 window:
        //   row r, col 0 (最新) = 最后一次 feed 的值 = r*10+4
        //   row r, col 4 (最旧) = 第一次 feed 的值 = r*10+0
        for (row <- 0 until 5) {
          dut.io.window(row)(0).expect((row * 10 + 4).S)   // c0 = 最新
          dut.io.window(row)(4).expect((row * 10 + 0).S)   // c4 = 最旧
          // 中间列也验证一下
          dut.io.window(row)(1).expect((row * 10 + 3).S)   // c1 = 倒数第二新
          dut.io.window(row)(2).expect((row * 10 + 2).S)   // c2 = 中间
          dut.io.window(row)(3).expect((row * 10 + 1).S)   // c3 = 第二旧
        }

        println("--- SUCCESS: ShiftWindow shift 方向正确 ---")
      }
    }

    "colValid=false 时 column 0 补零，其余列继续右移" in {
      simulate(new ShiftWindow) { dut =>
        // 先喂 2 拍真实数据
        for (feed <- 0 until 2) {
          for (row <- 0 until 5) {
            dut.io.colIn(row).poke((row * 10 + feed).S)
          }
          dut.io.colValid.poke(true.B)
          dut.clock.step()
        }

        // 现在 row 0 的窗口: c0=1, c1=0, c2=c3=c4=0

        // 喂 1 拍 colValid=false，colIn 随便给
        dut.io.colValid.poke(false.B)
        for (row <- 0 until 5) {
          dut.io.colIn(row).poke(999.S) // 不会被用
        }
        dut.clock.step()

        // 所有行 c0 应该全零（被 colValid=false 覆盖），c1..c4 继续右移
        for (row <- 0 until 5) {
          dut.io.window(row)(0).expect(0.S)               // colValid=false → 补零
          dut.io.window(row)(1).expect((row * 10 + 1).S)  // 第二拍的数据右移到了 c1
          dut.io.window(row)(2).expect((row * 10 + 0).S)  // 第一拍的数据右移到了 c2
          dut.io.window(row)(3).expect(0.S)               // 初始零右移到了 c3
          dut.io.window(row)(4).expect(0.S)               // 初始零右移到了 c4
        }

        println("--- SUCCESS: colValid=false 补零 + 继续右移 ---")
      }
    }
  }
}
