package matrix

import chisel3._
import chisel3.util._

class ShiftWindow extends Module {
  val io = IO(new Bundle {
    val colIn    = Input(Vec(5, SInt(16.W)))
    val colValid = Input(Bool())
    val window   = Output(Vec(5, Vec(5, SInt(16.W))))
  })

  // 5×5 register window, reset to zero
  val reg = RegInit(VecInit.tabulate(5, 5) { (_, _) => 0.S(16.W) })

  // Shift logic: colValid=true → shift in new column from left
  //              colValid=false → shift with zeros
  // c0 is newest column, c4 is oldest
  when(io.colValid){
    for(row <- 0 until 5){
      reg(row)(4) := reg(row)(3)
      reg(row)(3) := reg(row)(2)
      reg(row)(2) := reg(row)(1)
      reg(row)(1) := reg(row)(0)
      reg(row)(0) := io.colIn(row)
    }
  }.otherwise{
    for(row <- 0 until 5){
      reg(row)(4) := reg(row)(3)
      reg(row)(3) := reg(row)(2)
      reg(row)(2) := reg(row)(1)
      reg(row)(1) := reg(row)(0)
      reg(row)(0) := 0.S
    }
  }

  // Combinational output of current window
  io.window := reg
}
