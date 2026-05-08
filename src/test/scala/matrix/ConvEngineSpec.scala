package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.io.Source

class ConvEngineSpec extends AnyFreeSpec with Matchers with ChiselSim {

  // 读 hex 文件，每行一个 16-bit hex 值，返回有符号整数序列
  def readHex(path: String): Seq[Int] = {
    val src = Source.fromFile(path)
    try {
      src.getLines().toSeq.map { line =>
        val v = Integer.parseInt(line.trim(), 16)
        if ((v & 0x8000) != 0) v | 0xFFFF0000 else v
      }
    } finally src.close()
  }

  // 资源路径前缀
  val resPath = "src/test/resources/"

  "ConvEngine" - {

    "TC-CONV-01 identity kernel: 输出 = 输入" in {
      simulate(new ConvEngine) { dut =>
        val kernelHex   = readHex(resPath + "identity_kernel_kernel.hex")
        val inputHex    = readHex(resPath + "identity_kernel_input.hex")
        val expectedHex = readHex(resPath + "identity_kernel_expected.hex")

        // step 1: 写 kernel（25 个权值）
        for (addr <- 0 until 25) {
          dut.io.kernelWe.poke(true.B)
          dut.io.kernelAddr.poke(addr.U)
          dut.io.kernelData.poke(kernelHex(addr).S)
          dut.clock.step()
        }
        dut.io.kernelWe.poke(false.B)

        // step 2: 构建 36×36 padded 图像（上下左右各 2 行/列零）
        val padded = Array.fill(36, 36)(0)
        for (r <- 0 until 32; c <- 0 until 32) {
          padded(r + 2)(c + 2) = inputHex(r * 32 + c)
        }

        // step 3: 逐行逐列喂入，每行 36 + 5 drain = 41 列
        val results = scala.collection.mutable.ArrayBuffer[Int]()
        var cycle = 0
        val watchdog = 6000

        for (outR <- 0 until 32; pc <- 0 until 41 if cycle < watchdog) {
          val inImage = pc < 36
          // colValid 要送到 pc=35 才能让窗口中心覆盖到最后一列图像
          val isValid = (pc >= 2 && pc < 36)

          for (dr <- 0 until 5) {
            val v = if (inImage) padded(outR + dr)(pc) else 0
            dut.io.colIn(dr).poke(v.S)
          }
          dut.io.colValid.poke(isValid.B)
          dut.clock.step()
          cycle += 1

          if (dut.io.outValid.peek().litToBoolean) {
            val v = dut.io.result.peek().litValue.toInt
            results += (if ((v & 0x8000) != 0) v | 0xFFFF0000 else v)
          }
        }

        // 每行 34 个 outValid（pc=7..40），丢弃前 2 个（窗口中心还在左 padding）
        val aligned = results.grouped(34).flatMap(_.drop(2)).take(1024).toSeq

        assert(cycle < watchdog, s"watchdog at cycle $cycle")
        assert(aligned.length == 1024, s"expected 1024 aligned, got ${aligned.length}")

        var mismatch = 0
        for (i <- 0 until 1024) {
          if (aligned(i) != expectedHex(i)) mismatch += 1
        }
        println(s"TC-CONV-01 mismatches: $mismatch / 1024")
        assert(mismatch == 0, s"$mismatch mismatches")
      }
    }

    "TC-CONV-02 box blur" in {
      simulate(new ConvEngine) { dut =>
        val kernelHex   = readHex(resPath + "box_blur_ones_kernel.hex")
        val inputHex    = readHex(resPath + "box_blur_ones_input.hex")
        val expectedHex = readHex(resPath + "box_blur_ones_expected.hex")

        for (addr <- 0 until 25) {
          dut.io.kernelWe.poke(true.B)
          dut.io.kernelAddr.poke(addr.U)
          dut.io.kernelData.poke(kernelHex(addr).S)
          dut.clock.step()
        }
        dut.io.kernelWe.poke(false.B)

        val padded = Array.fill(36, 36)(0)
        for (r <- 0 until 32; c <- 0 until 32) {
          padded(r + 2)(c + 2) = inputHex(r * 32 + c)
        }

        val results = scala.collection.mutable.ArrayBuffer[Int]()
        var cycle = 0
        val watchdog = 6000

        for (outR <- 0 until 32; pc <- 0 until 41 if cycle < watchdog) {
          val inImage = pc < 36
          for (dr <- 0 until 5) {
            val v = if (inImage) padded(outR + dr)(pc) else 0
            dut.io.colIn(dr).poke(v.S)
          }
          dut.io.colValid.poke((pc >= 2 && pc < 36).B)
          dut.clock.step()
          cycle += 1

          if (dut.io.outValid.peek().litToBoolean) {
            val v = dut.io.result.peek().litValue.toInt
            results += (if ((v & 0x8000) != 0) v | 0xFFFF0000 else v)
          }
        }

        val aligned = results.grouped(34).flatMap(_.drop(2)).take(1024).toSeq
        assert(cycle < watchdog, "watchdog")
        assert(aligned.length == 1024, s"expected 1024 aligned, got ${aligned.length}")
        var mismatch = 0
        for (i <- 0 until 1024) {
          if (aligned(i) != expectedHex(i)) mismatch += 1
        }
        println(s"TC-CONV-02 mismatches: $mismatch / 1024")
        assert(mismatch == 0, s"$mismatch mismatches")
      }
    }

    "TC-CONV-03 edge detect (sobel)" in {
      simulate(new ConvEngine) { dut =>
        val kernelHex   = readHex(resPath + "edge_detect_sobel_kernel.hex")
        val inputHex    = readHex(resPath + "edge_detect_sobel_input.hex")
        val expectedHex = readHex(resPath + "edge_detect_sobel_expected.hex")

        for (addr <- 0 until 25) {
          dut.io.kernelWe.poke(true.B)
          dut.io.kernelAddr.poke(addr.U)
          dut.io.kernelData.poke(kernelHex(addr).S)
          dut.clock.step()
        }
        dut.io.kernelWe.poke(false.B)

        val padded = Array.fill(36, 36)(0)
        for (r <- 0 until 32; c <- 0 until 32) {
          padded(r + 2)(c + 2) = inputHex(r * 32 + c)
        }

        val results = scala.collection.mutable.ArrayBuffer[Int]()
        var cycle = 0
        val watchdog = 6000

        for (outR <- 0 until 32; pc <- 0 until 41 if cycle < watchdog) {
          val inImage = pc < 36
          for (dr <- 0 until 5) {
            val v = if (inImage) padded(outR + dr)(pc) else 0
            dut.io.colIn(dr).poke(v.S)
          }
          dut.io.colValid.poke((pc >= 2 && pc < 36).B)
          dut.clock.step()
          cycle += 1

          if (dut.io.outValid.peek().litToBoolean) {
            val v = dut.io.result.peek().litValue.toInt
            results += (if ((v & 0x8000) != 0) v | 0xFFFF0000 else v)
          }
        }

        val aligned = results.grouped(34).flatMap(_.drop(2)).take(1024).toSeq
        assert(cycle < watchdog, "watchdog")
        assert(aligned.length == 1024, s"expected 1024 aligned, got ${aligned.length}")
        var mismatch = 0
        for (i <- 0 until 1024) {
          if (aligned(i) != expectedHex(i)) mismatch += 1
        }
        println(s"TC-CONV-03 mismatches: $mismatch / 1024")
        assert(mismatch == 0, s"$mismatch mismatches")
      }
    }

    "TC-CONV-04 saturation" in {
      simulate(new ConvEngine) { dut =>
        val kernelHex   = readHex(resPath + "saturation_test_kernel.hex")
        val inputHex    = readHex(resPath + "saturation_test_input.hex")
        val expectedHex = readHex(resPath + "saturation_test_expected.hex")

        for (addr <- 0 until 25) {
          dut.io.kernelWe.poke(true.B)
          dut.io.kernelAddr.poke(addr.U)
          dut.io.kernelData.poke(kernelHex(addr).S)
          dut.clock.step()
        }
        dut.io.kernelWe.poke(false.B)

        val padded = Array.fill(36, 36)(0)
        for (r <- 0 until 32; c <- 0 until 32) {
          padded(r + 2)(c + 2) = inputHex(r * 32 + c)
        }

        val results = scala.collection.mutable.ArrayBuffer[Int]()
        var cycle = 0
        val watchdog = 6000

        for (outR <- 0 until 32; pc <- 0 until 41 if cycle < watchdog) {
          val inImage = pc < 36
          for (dr <- 0 until 5) {
            val v = if (inImage) padded(outR + dr)(pc) else 0
            dut.io.colIn(dr).poke(v.S)
          }
          dut.io.colValid.poke((pc >= 2 && pc < 36).B)
          dut.clock.step()
          cycle += 1

          if (dut.io.outValid.peek().litToBoolean) {
            val v = dut.io.result.peek().litValue.toInt
            results += (if ((v & 0x8000) != 0) v | 0xFFFF0000 else v)
          }
        }

        val aligned = results.grouped(34).flatMap(_.drop(2)).take(1024).toSeq
        assert(cycle < watchdog, "watchdog")
        assert(aligned.length == 1024, s"expected 1024 aligned, got ${aligned.length}")
        var mismatch = 0
        for (i <- 0 until 1024) {
          if (aligned(i) != expectedHex(i)) mismatch += 1
        }
        println(s"TC-CONV-04 mismatches: $mismatch / 1024")
        assert(mismatch == 0, s"$mismatch mismatches")
      }
    }
  }
}
