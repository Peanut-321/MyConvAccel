package matrix

import chisel3._
import chisel3.util._

class ShiftWindow extends Module {
  val io = IO(new Bundle {
    val colIn    = Input(Vec(5, SInt(16.W)))
    val colValid = Input(Bool())
    val window   = Output(Vec(5, Vec(5, SInt(16.W))))
  })

  // 5×5 寄存器窗口，复位后全零
  val reg = RegInit(VecInit.tabulate(5, 5) { (_, _) => 0.S(16.W) })

  // TODO: 每拍 shift 逻辑
  //   colValid = true  → c4←c3←c2←c1←c0←colIn
  //   colValid = false → c4←c3←c2←c1←c0←0
  //
  // 注意：c0 是最新列（colIn 写入），c4 是最旧列（右移推出）
  // 用 := 默认值 + when 覆盖的风格
  
  when(io.colValid){
    for(row <- 0 until 5){
      reg(row)(4) := reg(row)(3)
      reg(row)(3) := reg(row)(2)
      reg(row)(2) := reg(row)(1)
      reg(row)(1) := reg(row)(0)
      reg(row)(0) := io.colIn(row)
    }
  }otherwise{
    for(row <- 0 until 5){
      reg(row)(4) := reg(row)(3)
      reg(row)(3) := reg(row)(2)
      reg(row)(2) := reg(row)(1)
      reg(row)(1) := reg(row)(0)
      reg(row)(0) := 0.S
    }
  }
  

  // 组合输出当前窗口值
  io.window := reg
}
