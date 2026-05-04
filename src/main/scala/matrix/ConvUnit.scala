package matrix

import chisel3._
import chisel3.util._

class ConvUnit extends Module {
  val io = IO(new Bundle {
    val pixels  = Input(Vec(25, SInt(16.W)))  // 25个像素输入
    val weights = Input(Vec(25, SInt(16.W)))  // 25个权重输入
    val result  = Output(SInt(16.W))          // 卷积结果输出
  })

  // 1. 并行计算乘法 (Q8.8 * Q8.8 = Q16.16)
  val products = Wire(Vec(25, SInt(32.W)))
  for (i <- 0 until 25) {
    products(i) := io.pixels(i) * io.weights(i)
  }

  // 2. 累加 25 个乘积
  val sum = products.reduce(_ + _)

  // 3. 将结果转回 Q8.8 (右移 8 位)
  val res32 = sum >> 8

  // 4. 饱和处理逻辑 (防止数值溢出)
  val finalResult = Wire(SInt(16.W))
  when(res32 > 32767.S) {
    finalResult := 32767.S
  }.elsewhen(res32 < -32768.S) {
    finalResult := -32768.S
  }.otherwise {
    finalResult := res32.asSInt
  }

  io.result := finalResult
}
