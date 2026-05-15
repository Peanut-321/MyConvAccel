package matrix

import chisel3._
import chisel3.util._

class LineBuffer extends Module {
  val io = IO(new Bundle {

    // 输入（来自DMA）
    val in       = Flipped(Decoupled(UInt(16.W)))

    // 输出（去ConvEngine）
    val colOut   = Output(Vec(5, SInt(16.W)))   //五个垂直相邻像素
    val colValid = Output(Bool())               //当前列是否有效

    // 控制（与顶层FSM的控制握手）
    val start    = Input(Bool())                //顶层 FSM 发出启动脉冲
    val done     = Output(Bool())               //32行全部输出完毕
    val stall    = Input(Bool())                //下游反压，暂停输出
  })

  // 5 × 32 寄存器缓冲(5行，32列)
  val buffer = Reg(Vec(5, Vec(32, SInt(16.W))))

  // DMA 加载暂存行 — sActive 期间逐像素填入新行，行尾移位时并入 buffer
  val tmpRow = Reg(Vec(32, SInt(16.W)))

  //FSM状态定义与初始化状态
  val sIdle :: sPrime :: sActive :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  //计数器
  val outputRow = RegInit(0.U(6.W))   // 当前输出行 0..31
  val outputCol = RegInit(0.U(6.W))   // 当前输出列 0..35
  val loadRow   = RegInit(0.U(3.W))   // DMA 正在加载的行号 0..4
  val loadCol   = RegInit(0.U(5.W))   // DMA 正在加载的列号

  io.done := state === sDone 
  io.in.ready := false.B
  io.colOut   := VecInit.fill(5)(0.S(16.W))
  io.colValid := false.B

  // FSM
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state      := sPrime
        outputRow  := 0.U
        outputCol  := 0.U
        loadRow    := 0.U
        loadCol    := 0.U
      }
    }

    //从DMA加载前五行元素到LineBuffer
    is(sPrime) {
      io.in.ready := true.B
      when(io.in.valid){
        buffer(loadRow)(loadCol) := io.in.bits.asSInt
        when(loadRow === 4.U && loadCol === 31.U){
          state := sActive
        }
        when(loadCol === 31.U){
          loadCol := 0.U
          loadRow := loadRow + 1.U
        }.otherwise{
          loadCol := loadCol + 1.U
        }
      }
    }

    //输出32行 x 36列
    is(sActive) {
      val bufCol  = (outputCol - 2.U)(4,0)  // 映射到 buffer 列索引 0..31, 截断至 5-bit
      val inImage = outputCol >= 2.U && outputCol <= 33.U

      // ── 列输出，含上下 padding ──
      when (inImage) {
        when (outputRow === 0.U) {
          // top 两行 padding
          io.colOut(0) := 0.S(16.W)
          io.colOut(1) := 0.S(16.W)
          io.colOut(2) := buffer(0)(bufCol)
          io.colOut(3) := buffer(1)(bufCol)
          io.colOut(4) := buffer(2)(bufCol)
        }.elsewhen (outputRow === 1.U) {
          // top 一行 padding
          io.colOut(0) := 0.S(16.W)
          io.colOut(1) := buffer(0)(bufCol)
          io.colOut(2) := buffer(1)(bufCol)
          io.colOut(3) := buffer(2)(bufCol)
          io.colOut(4) := buffer(3)(bufCol)
        }.elsewhen (outputRow === 30.U) {
          // bottom 一行 padding
          io.colOut(0) := buffer(0)(bufCol)
          io.colOut(1) := buffer(1)(bufCol)
          io.colOut(2) := buffer(2)(bufCol)
          io.colOut(3) := buffer(3)(bufCol)
          io.colOut(4) := 0.S(16.W)
        }.elsewhen (outputRow === 31.U) {
          // bottom 两行 padding
          io.colOut(0) := buffer(0)(bufCol)
          io.colOut(1) := buffer(1)(bufCol)
          io.colOut(2) := buffer(2)(bufCol)
          io.colOut(3) := 0.S(16.W)
          io.colOut(4) := 0.S(16.W)
        }.otherwise {
          // 中间行 2..29：顺序读出 buffer[0..4]
          for (i <- 0 until 5) {
            io.colOut(i) := buffer(i)(bufCol)
          }
        }
        io.colValid := true.B
      }.otherwise {
        // 左/右 padding 列：输出全零
        io.colOut   := VecInit.fill(5)(0.S(16.W))
        // 延长 colValid 2 拍到 34..35，让管线尾部 img_30/img_31 能被 outValid 标记
        io.colValid := outputCol >= 34.U && outputCol <= 35.U
      }

      // ── DMA 加载后续行（行 5..31）进入 tmpRow ──
      // 移位从 outputRow 2→3 开始，此时需要 img[5] 已备好
      // loadRow = outputRow + 3，需满足 loadRow < 32
      val needLoad = outputRow >= 2.U && outputRow + 3.U < 32.U
      io.in.ready := needLoad && inImage && !io.stall

      when (io.in.valid && io.in.ready) {
        tmpRow(loadCol) := io.in.bits.asSInt
        when (loadCol === 31.U) {
          loadCol := 0.U
        }.otherwise {
          loadCol := loadCol + 1.U
        }
      }

      // ── 计数器推进（反压门控）──
      when (!io.stall) {
        outputCol := outputCol + 1.U
        when (outputCol === 35.U) {
          outputCol := 0.U
          outputRow := outputRow + 1.U

          // 行 2→3 及之后：buffer 上移一行，tmpRow 进入 buffer(4)
          when (outputRow >= 2.U) {
            buffer(0) := buffer(1)
            buffer(1) := buffer(2)
            buffer(2) := buffer(3)
            buffer(3) := buffer(4)
            buffer(4) := tmpRow
          }

          when (outputRow === 31.U) {
            state := sDone
          }
        }
      }
    }

    //start下降后回到sIdle
    is(sDone) {
      when (!io.start) {
        state := sIdle
      }
    }
  }  

}
