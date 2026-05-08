package matrix

import chisel3._
import chisel3.util._

class ConvUnit extends Module {
  // // ── Phase 2 纯组合版本（保留作参考）───────────────────────
  // val io = IO(new Bundle {
  //   val pixels  = Input(Vec(25, SInt(16.W)))  // 25个像素输入
  //   val weights = Input(Vec(25, SInt(16.W)))  // 25个权重输入
  //   val result  = Output(SInt(16.W))          // 卷积结果输出
  // })
  //
  // // 1. 并行计算乘法 (Q8.8 * Q8.8 = Q16.16)
  // val products = Wire(Vec(25, SInt(32.W)))
  // for (i <- 0 until 25) {
  //   products(i) := io.pixels(i) * io.weights(i)
  // }
  //
  // // 2. 累加 25 个乘积
  // val sum = products.reduce(_ + _)
  //
  // // 3. 将结果转回 Q8.8 (右移 8 位)
  // val res32 = sum >> 8
  //
  // // 4. 饱和处理逻辑 (防止数值溢出)
  // val finalResult = Wire(SInt(16.W))
  // when(res32 > 32767.S) {
  //   finalResult := 32767.S
  // }.elsewhen(res32 < -32768.S) {
  //   finalResult := -32768.S
  // }.otherwise {
  //   finalResult := res32.asSInt
  // }
  //
  // io.result := finalResult
  // // ─────────────────────────────────────────────────────────

  // ── Phase 6: 5 级流水线 ──
  val io = IO(new Bundle {
    val window   = Input(Vec(5, Vec(5, SInt(16.W))))
    val kernel   = Input(Vec(5, Vec(5, SInt(16.W))))
    val inValid  = Input(Bool())
    val outValid = Output(Bool())
    val result   = Output(SInt(16.W))
  })

  // ── Stage 0 (组合): 25 并行乘法 ──
  val s0_prod = Wire(Vec(25, SInt(32.W)))
  for (r <- 0 until 5; c <- 0 until 5) {
    s0_prod(r * 5 + c) := io.window(r)(c) * io.kernel(r)(c)
  }

  // ── Stage 1 (寄存): 25 → 13 pairwise sum ──
  val s1_in = Wire(Vec(13, SInt(32.W)))
  for (i <- 0 until 12) {
    s1_in(i) := s0_prod(2 * i) + s0_prod(2 * i + 1)
  }
  s1_in(12) := s0_prod(24)
  val s1_reg = RegNext(s1_in)

  // ── Stage 2 (寄存): 13 → 7 ──
  val s2_in = Wire(Vec(7, SInt(32.W)))
  for (i <- 0 until 6) {
    s2_in(i) := s1_reg(2 * i) + s1_reg(2 * i + 1)
  }
  s2_in(6) := s1_reg(12)
  val s2_reg = RegNext(s2_in)

  // ── Stage 3 (寄存): 7 → 4 ──
  val s3_in = Wire(Vec(4, SInt(32.W)))
  for (i <- 0 until 3) {
    s3_in(i) := s2_reg(2 * i) + s2_reg(2 * i + 1)
  }
  s3_in(3) := s2_reg(6)
  val s3_reg = RegNext(s3_in)

  // ── Stage 4 (寄存): 4 → 2 ──
  val s4_in = Wire(Vec(2, SInt(32.W)))
  s4_in(0) := s3_reg(0) + s3_reg(1)
  s4_in(1) := s3_reg(2) + s3_reg(3)
  val s4_reg = RegNext(s4_in)

  // ── Stage 5 (寄存): 2 → 1, 四舍五入, 饱和 ──
  val sum     = s4_reg(0) + s4_reg(1)
  val rounded = Wire(SInt(32.W))
  rounded := (sum + (1 << 7).S) >> 8

  val saturated = Wire(SInt(16.W))
  when(rounded > 32767.S) {
    saturated := 32767.S
  }.elsewhen(rounded < -32768.S) {
    saturated := -32768.S
  }.otherwise {
    saturated := rounded.asSInt
  }
  val s5_result = RegNext(saturated)

  io.result := s5_result

  // ── inValid 传播, 延迟 5 拍 ──
  io.outValid := ShiftRegister(io.inValid, 5, false.B, true.B)
}
