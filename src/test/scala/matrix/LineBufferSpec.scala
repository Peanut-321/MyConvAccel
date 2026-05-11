package matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class LineBufferSpec extends AnyFreeSpec with Matchers with ChiselSim {

  // 喂 DMA 像素直到 io.in.ready 为低或全部喂完
  def feedPixel(dut: LineBuffer, value: Int, checkReady: Boolean = true): Unit = {
    if (checkReady) dut.io.in.ready.expect(true.B)
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.poke(value.U(16.W))
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  // 喂满一整行 32 像素（值连续递增，方便验证）
  def feedRow(dut: LineBuffer, base: Int): Unit = {
    for (c <- 0 until 32) {
      feedPixel(dut, base + c)
    }
  }

  // 消费一列输出并推进时钟，返回 (colValid, 5 个像素值)
  def readColumn(dut: LineBuffer): (Boolean, Seq[Int]) = {
    val valid = dut.io.colValid.peekValue().asBigInt.intValue != 0
    val pixels = (0 until 5).map { i =>
      dut.io.colOut(i).peekValue().asBigInt.toInt
    }
    dut.clock.step()
    (valid, pixels)
  }

  "LineBuffer" - {

    "TC-LB-01: prime 5 rows and output first row with left/right padding" in {
      simulate(new LineBuffer) { dut =>
        dut.io.stall.poke(false.B)
        dut.io.start.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // sPrime: 喂 5 行像素，行 r 列 c = r*32 + c
        for (r <- 0 until 5) {
          for (c <- 0 until 32) {
            feedPixel(dut, r * 32 + c)
          }
        }

        // sActive: 验证第 0 行的 36 列 colValid 时序
        val row0 = (0 until 36).map(_ => readColumn(dut))

        // 左 padding (列 0,1): colValid = false
        assert(row0(0)._1 == false,  "col 0 should be left padding")
        assert(row0(1)._1 == false,  "col 1 should be left padding")

        // 图像列 (2..33): colValid = true, 且值正确
        for (c <- 0 until 32) {
          val idx = c + 2
          assert(row0(idx)._1 == true, s"col $idx should be valid image")
          // outputRow=0: colOut = (0, 0, img[0][c], img[1][c], img[2][c])
          val expected = Seq(0, 0, c, 32 + c, 64 + c)
          assert(row0(idx)._2 == expected,
            s"col $idx: expected $expected, got ${row0(idx)._2}")
        }

        // 右 padding (列 34,35): colValid = false
        assert(row0(34)._1 == false, "col 34 should be right padding")
        assert(row0(35)._1 == false, "col 35 should be right padding")
      }
    }

    "TC-LB-02: top padding — rows 0 and 1 handle padding correctly" in {
      simulate(new LineBuffer) { dut =>
        dut.io.stall.poke(false.B)
        dut.io.start.poke(false.B)
        dut.clock.step()
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 喂 5 行
        for (r <- 0 until 5) { feedRow(dut, r * 32) }

        // Row 0: col(0), col(1) should be 0
        // Skip pad cols, check col 2 (first image col)
        for (_ <- 0 until 2) { readColumn(dut) } // skip pad
        val r0c2 = readColumn(dut)
        assert(r0c2._2(0) == 0, s"row0 colOut(0) should be 0 (top pad)")
        assert(r0c2._2(1) == 0, s"row0 colOut(1) should be 0 (top pad)")

        // Skip to row 1, col 2 (skip remaining cols of row 0 + 2 pad cols of row 1)
        for (_ <- 0 until 33) { readColumn(dut) } // finish row 0 (33 cols left)
        for (_ <- 0 until 2)  { readColumn(dut) } // skip row 1 pad
        val r1c2 = readColumn(dut)
        assert(r1c2._2(0) == 0, s"row1 colOut(0) should be 0 (top pad)")
        assert(r1c2._2(1) == 0, s"row1 colOut(1) = img[0] should be 0? No — check")
        // row1 colOut: (0, img[0][c], img[1][c], img[2][c], img[3][c])
        // col=2, bufCol=0 → (0, 0, 32, 64, 96)
        assert(r1c2._2(4) == 96, s"row1 colOut(4) should be img[3][0]=96")
      }
    }

    "TC-LB-03: full 32 rows — done asserts after last row" in {
      simulate(new LineBuffer) { dut =>
        dut.io.stall.poke(false.B)
        dut.io.start.poke(false.B)
        dut.clock.step()
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 喂全部 32 行 (1024 像素) — LineBuffer 内部管理反压
        var sent = 0
        var recvRow = 0
        var done = false
        var cycle = 0
        val watchdog = 5000

        while (!done && cycle < watchdog) {
          // 如果 DMA 准备好且有像素待发，发送
          if (sent < 1024 && dut.io.in.ready.peekValue().asBigInt.intValue != 0) {
            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.poke(sent.U(16.W))
            sent += 1
          } else {
            dut.io.in.valid.poke(false.B)
          }

          done = dut.io.done.peekValue().asBigInt.intValue != 0
          dut.clock.step()
          cycle += 1
        }

        // sent 1024 像素说明 DMA 所有数据都已被接收
        assert(sent == 1024, s"all 1024 pixels should be sent, got $sent")
        assert(done, "done should be asserted")
        assert(cycle < watchdog, s"timed out after $cycle cycles")
      }
    }

    "TC-LB-04: bottom padding — rows 30 and 31 have zeros" in {
      simulate(new LineBuffer) { dut =>
        dut.io.stall.poke(false.B)
        dut.io.start.poke(false.B)
        dut.clock.step()
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        var sent = 0
        var outputRow = 0
        var outputCol = 0
        var done = false
        var cycle = 0
        val watchdog = 5000

        // 探测 sPrime→sActive 转换，避免把 sPrime 周期计入输出列
        var wasReady  = false
        var inActive  = false

        while (!done && cycle < watchdog) {
          if (sent < 1024 && dut.io.in.ready.peekValue().asBigInt.intValue != 0) {
            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.poke(sent.U(16.W))
            sent += 1
          } else {
            dut.io.in.valid.poke(false.B)
          }

          val isReady = dut.io.in.ready.peekValue().asBigInt.intValue != 0
          if (!inActive && wasReady && !isReady) {
            // ready 由高变低 → sPrime 结束，sActive 开始
            inActive = true
          }
          wasReady = isReady

          if (inActive) {
            val valid = dut.io.colValid.peekValue().asBigInt.intValue != 0
            if (valid) {
              val pixels = (0 until 5).map { i =>
                dut.io.colOut(i).peekValue().asBigInt.toInt
              }
              if (outputRow == 30) {
                // row 30: colOut = (img28, img29, img30, img31, 0)
                assert(pixels(4) == 0, s"row30 colOut(4) should be 0, got ${pixels(4)}")
              }
              if (outputRow == 31) {
                // row 31: colOut = (img29, img30, img31, 0, 0)
                assert(pixels(3) == 0, s"row31 colOut(3) should be 0, got ${pixels(3)}")
                assert(pixels(4) == 0, s"row31 colOut(4) should be 0, got ${pixels(4)}")
              }
            }

            outputCol += 1
            if (outputCol == 36) {
              outputCol = 0
              outputRow += 1
            }
          }

          done = dut.io.done.peekValue().asBigInt.intValue != 0
          dut.clock.step()
          cycle += 1
        }

        assert(outputRow == 32, s"should output 32 rows, got $outputRow")
        assert(done)
      }
    }

    "TC-LB-05: stall pauses output and resumes correctly" in {
      simulate(new LineBuffer) { dut =>
        dut.io.stall.poke(false.B)
        dut.io.start.poke(false.B)
        dut.clock.step()
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 喂 5 行
        for (r <- 0 until 5) { feedRow(dut, r * 32) }

        // 读 row 0 前 3 个图像列 (跳过 2 pad)
        for (_ <- 0 until 2) { readColumn(dut) }
        val beforeStall = (0 until 3).map(_ => readColumn(dut)._2)

        // 拉高 stall — 输出应暂停
        dut.io.stall.poke(true.B)
        val stalled = readColumn(dut)
        // 暂停后的输出应该不变（同一个 colOut 被重新读取）
        dut.clock.step()
        val stalled2 = readColumn(dut)
        assert(stalled._2 == stalled2._2, "output should not change while stalled")

        // 释放 stall — 输出恢复
        dut.io.stall.poke(false.B)
        val afterStall = readColumn(dut)
        // 恢复后第一列应该继续
        assert(afterStall._1 == true, "should output valid data after stall release")
      }
    }
  }
}
