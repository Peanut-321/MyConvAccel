package matrix

import chisel3._
import chisel3.util._

class LineBuffer extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(UInt(16.W)))
    val colOut    = Output(Vec(5, SInt(16.W)))
    val colValid  = Output(Bool())
    val storeValid = Output(Bool())
    val start     = Input(Bool())
    val done      = Output(Bool())
    val stall     = Input(Bool())
  })

  val buffer = Reg(Vec(5, Vec(32, SInt(16.W))))
  val tmpRow = Reg(Vec(32, SInt(16.W)))

  val sIdle :: sPrime :: sActive :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val outputRow = RegInit(0.U(6.W))
  val outputCol = RegInit(0.U(6.W))
  val loadRow   = RegInit(0.U(3.W))
  val loadCol   = RegInit(0.U(5.W))

  val dbgLineHitCnt = RegInit(0.U(6.W))

  io.done       := state === sDone
  io.in.ready   := false.B
  io.colOut     := VecInit.fill(5)(0.S(16.W))
  io.colValid   := false.B
  io.storeValid := false.B

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state     := sPrime
        outputRow := 0.U
        outputCol := 0.U
        loadRow   := 0.U
        loadCol   := 0.U
        for (r <- 0 until 5) {
          for (c <- 0 until 32) { buffer(r)(c) := 0.S }
        }
        for (c <- 0 until 32) { tmpRow(c) := 0.S }
        dbgLineHitCnt := 0.U
      }
    }

    is(sPrime) {
      io.in.ready := true.B
      when(io.in.valid) {
        buffer(loadRow)(loadCol) := io.in.bits.asSInt
        when(loadRow === 4.U && loadCol === 31.U) { state := sActive }
        when(loadCol === 31.U) {
          loadCol := 0.U
          loadRow := loadRow + 1.U
        }.otherwise { loadCol := loadCol + 1.U }
      }
    }

    is(sActive) {
      val bufCol  = (outputCol - 2.U)(4, 0)
      val inImage = outputCol >= 2.U && outputCol <= 33.U

      val needLoad  = outputRow >= 2.U && outputRow + 3.U < 32.U
      val waitInput = needLoad && inImage && !io.in.valid
      val advance   = !io.stall && !waitInput

      when(outputRow>=28.U && outputCol>=30.U){
        printf("[TAIL-LB] row=%d col=%d colValid=%d\n", outputRow, outputCol, io.colValid)}

      when(inImage) {
        when(outputRow === 0.U) {
          io.colOut(0) := 0.S(16.W); io.colOut(1) := 0.S(16.W)
          io.colOut(2) := buffer(0)(bufCol); io.colOut(3) := buffer(1)(bufCol); io.colOut(4) := buffer(2)(bufCol)
        }.elsewhen(outputRow === 1.U) {
          io.colOut(0) := 0.S(16.W)
          io.colOut(1) := buffer(0)(bufCol); io.colOut(2) := buffer(1)(bufCol); io.colOut(3) := buffer(2)(bufCol); io.colOut(4) := buffer(3)(bufCol)
        }.elsewhen(outputRow === 30.U) {
          io.colOut(0) := buffer(1)(bufCol); io.colOut(1) := buffer(2)(bufCol); io.colOut(2) := buffer(3)(bufCol)
          io.colOut(3) := 0.S(16.W); io.colOut(4) := 0.S(16.W)
        }.elsewhen(outputRow === 31.U) {
          io.colOut(0) := buffer(2)(bufCol); io.colOut(1) := buffer(3)(bufCol); io.colOut(2) := buffer(4)(bufCol)
          io.colOut(3) := 0.S(16.W); io.colOut(4) := 0.S(16.W)
        }.otherwise {
          for (i <- 0 until 5) { io.colOut(i) := buffer(i)(bufCol) }
        }
        io.colValid   := true.B
        io.storeValid := true.B
      }.otherwise {
        io.colOut := VecInit.fill(5)(0.S(16.W))
        io.colValid   := outputCol >= 34.U && outputCol <= 35.U
        io.storeValid := false.B
      }

      when(!advance) {
        io.colValid   := false.B
        io.storeValid := false.B
      }

      when(io.colValid && io.colOut.map(_ =/= 0.S).reduce(_ || _) && dbgLineHitCnt < 20.U) {
        printf("[LINE-HIT]")
        printf("hit=%d ", dbgLineHitCnt)
        printf("outputRow=%d ", outputRow)
        printf("outputCol=%d ", outputCol)
        printf("col0=%d ", io.colOut(0))
        printf("col1=%d ", io.colOut(1))
        printf("col2=%d ", io.colOut(2))
        printf("col3=%d ", io.colOut(3))
        printf("col4=%d\n", io.colOut(4))
        dbgLineHitCnt := dbgLineHitCnt + 1.U
      }

      when(io.start) { dbgLineHitCnt := 0.U }

      when(outputRow >= 30.U){
        printf("[TAIL] row=%d col=%d inImage=%d colvalid=%d advance=%d   stall=%d\n",outputRow,outputCol,inImage,io.colValid,advance,io.stall)}

      io.in.ready := needLoad && inImage && !io.stall

      when(io.in.valid && io.in.ready) {
        tmpRow(loadCol) := io.in.bits.asSInt
        when(loadCol === 31.U) { loadCol := 0.U }.otherwise { loadCol := loadCol + 1.U }
      }

      when(advance) {
        when(outputCol === 35.U) {
          outputCol := 0.U
          outputRow := outputRow + 1.U
          when(outputRow >= 2.U && outputRow + 3.U < 32.U) {
            buffer(0) := buffer(1); buffer(1) := buffer(2); buffer(2) := buffer(3); buffer(3) := buffer(4); buffer(4) := tmpRow
          }
          when(outputRow === 31.U) { state := sDone }
        }.otherwise { outputCol := outputCol + 1.U }
      }
    }

    is(sDone) {
      when(!io.start) { state := sIdle }
    }
  }
}
