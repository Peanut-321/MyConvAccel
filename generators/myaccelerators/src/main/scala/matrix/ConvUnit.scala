package matrix

import chisel3._
import chisel3.util._

class ConvUnit extends Module {
  val io = IO(new Bundle {
    val window   = Input(Vec(5, Vec(5, SInt(16.W))))
    val kernel   = Input(Vec(5, Vec(5, SInt(16.W))))
    val inValid  = Input(Bool())
    val outValid = Output(Bool())
    val result   = Output(SInt(16.W))
  })

  // Stage 0 (combinational): 25 parallel multiplies
  val s0_prod = Wire(Vec(25, SInt(32.W)))
  for (r <- 0 until 5; c <- 0 until 5) {
    s0_prod(r * 5 + c) := io.window(r)(c) * io.kernel(r)(c)
  }

  val anyProdNonZero = s0_prod.map(_ =/= 0.S).reduce(_ || _)
  val dbgProdHitCnt = RegInit(0.U(6.W))

  when(io.inValid && anyProdNonZero && dbgProdHitCnt < 20.U) {
    printf("[PROD-HIT] ")
    printf("hit=%d ", dbgProdHitCnt)
    printf("p0=%d ", s0_prod(0))
    printf("p1=%d ", s0_prod(1))
    printf("p2=%d ", s0_prod(2))
    printf("p3=%d ", s0_prod(3))
    printf("p4=%d ", s0_prod(4))
    printf("p5=%d ", s0_prod(5))
    printf("p6=%d ", s0_prod(6))
    printf("p7=%d ", s0_prod(7))
    printf("p8=%d ", s0_prod(8))
    printf("p9=%d ", s0_prod(9))
    printf("p10=%d ", s0_prod(10))
    printf("p11=%d ", s0_prod(11))
    printf("p12=%d ", s0_prod(12))
    printf("p13=%d ", s0_prod(13))
    printf("p14=%d ", s0_prod(14))
    printf("p15=%d ", s0_prod(15))
    printf("p16=%d ", s0_prod(16))
    printf("p17=%d ", s0_prod(17))
    printf("p18=%d ", s0_prod(18))
    printf("p19=%d ", s0_prod(19))
    printf("p20=%d ", s0_prod(20))
    printf("p21=%d ", s0_prod(21))
    printf("p22=%d ", s0_prod(22))
    printf("p23=%d ", s0_prod(23))
    printf("p24=%d\n", s0_prod(24))
    dbgProdHitCnt := dbgProdHitCnt + 1.U
  }

  // Stage 1 (registered): 25 → 13 pairwise sum
  val s1_in = Wire(Vec(13, SInt(32.W)))
  for (i <- 0 until 12) { s1_in(i) := s0_prod(2 * i) + s0_prod(2 * i + 1) }
  s1_in(12) := s0_prod(24)
  val s1_reg = RegNext(s1_in)

  // Stage 2: 13 → 7
  val s2_in = Wire(Vec(7, SInt(32.W)))
  for (i <- 0 until 6) { s2_in(i) := s1_reg(2 * i) + s1_reg(2 * i + 1) }
  s2_in(6) := s1_reg(12)
  val s2_reg = RegNext(s2_in)

  // Stage 3: 7 → 4
  val s3_in = Wire(Vec(4, SInt(32.W)))
  for (i <- 0 until 3) { s3_in(i) := s2_reg(2 * i) + s2_reg(2 * i + 1) }
  s3_in(3) := s2_reg(6)
  val s3_reg = RegNext(s3_in)

  // Stage 4: 4 → 2
  val s4_in = Wire(Vec(2, SInt(32.W)))
  s4_in(0) := s3_reg(0) + s3_reg(1)
  s4_in(1) := s3_reg(2) + s3_reg(3)
  val s4_reg = RegNext(s4_in)

  // Stage 5: 2 → 1, round, saturate
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

  val dbgConvPrintCnt = RegInit(0.U(6.W))
  when(io.outValid && dbgConvPrintCnt < 32.U) {
    printf("[CONV-DBG] ")
    printf("cnt=%d ", dbgConvPrintCnt)
    printf("w22=%d ", io.window(2)(2))
    printf("k22=%d ", io.kernel(2)(2))
    printf("sum=%d ", sum)
    printf("rounded=%d ", rounded)
    printf("saturated=%d ", saturated)
    printf("result=%d\n", io.result)
    dbgConvPrintCnt := dbgConvPrintCnt + 1.U
  }

  // inValid propagation, 5-cycle delay
  io.outValid := ShiftRegister(io.inValid, 5, false.B, true.B)

  val dbgResultHitCnt = RegInit(0.U(6.W))
  when(io.outValid && io.result =/= 0.S && dbgResultHitCnt < 20.U) {
    printf("[RESULT-HIT] ")
    printf("hit=%d ", dbgResultHitCnt)
    printf("sum=%d ", sum)
    printf("rounded=%d ", rounded)
    printf("saturated=%d ", saturated)
    printf("result=%d\n", io.result)
    dbgResultHitCnt := dbgResultHitCnt + 1.U
  }
}
