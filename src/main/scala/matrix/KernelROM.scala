package matrix

import chisel3._
import chisel3.util._

class KernelROM extends Module {
  val io = IO(new Bundle {
    val we     = Input(Bool())
    val wAddr  = Input(UInt(5.W))        // 0..24, row-major
    val wData  = Input(SInt(16.W))
    val kernel = Output(Vec(5, Vec(5, SInt(16.W))))
  })

  // 25 条目寄存器，复位全零
  val reg = RegInit(VecInit.tabulate(25) { _ => 0.S(16.W) })


  //kernel数据写入
  when(io.we){
    reg(io.wAddr) := io.wData
  }
  
  //kernel组合输出
  for(i <- 0 until 5){
    for(j <- 0 until 5){
      io.kernel(i)(j) := reg(i*5 + j)
    }
  }
}
