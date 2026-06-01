package matrix

import chisel3._
import chisel3.util._

class LineBuffer extends Module {
  val io = IO(new Bundle {
    // 输入（来自DMA）
    val in        = Flipped(Decoupled(UInt(16.W)))

    // 输出（去ConvEngine）
    val colOut    = Output(Vec(5, SInt(16.W)))   // 五个垂直相邻像素
    val colValid  = Output(Bool())               // 当前列是否送入ConvEngine

    // 输出（给Top层store控制）
    // colValid 可以包含 34/35 flush，用来把ConvUnit pipeline 尾部结果冲出来；
    // storeValid 只对应真正 32x32 输出区域，即 outputCol 2..33。
    val storeValid = Output(Bool())

    // 控制（与顶层FSM的控制握手）
    val start     = Input(Bool())                // 顶层 FSM 发出启动脉冲
    val done      = Output(Bool())               // 32行全部输出完毕
    val stall     = Input(Bool())                // 下游反压，暂停输出
  })

  // 5 x 32 寄存器缓冲（5行，32列）
  val buffer = Reg(Vec(5, Vec(32, SInt(16.W))))

  // DMA 加载暂存行
  // sActive 期间逐像素填入新行，行尾移位时并入 buffer
  val tmpRow = Reg(Vec(32, SInt(16.W)))

  // FSM状态定义与初始化状态
  val sIdle :: sPrime :: sActive :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // 计数器
  val outputRow = RegInit(0.U(6.W))   // 当前输出行 0..31
  val outputCol = RegInit(0.U(6.W))   // 当前输出列 0..35
  val loadRow   = RegInit(0.U(3.W))   // DMA 正在加载的行号 0..4
  val loadCol   = RegInit(0.U(5.W))   // DMA 正在加载的列号 0..31

  val dbgLineHitCnt = RegInit(0.U(6.W))

  io.done       := state === sDone
  io.in.ready   := false.B
  io.colOut     := VecInit.fill(5)(0.S(16.W))
  io.colValid   := false.B
  io.storeValid := false.B

  // ------------------------------------------------------------
  // FSM
  // ------------------------------------------------------------
  switch(state) {

    is(sIdle) {
      when(io.start) {
        state     := sPrime
        outputRow := 0.U
        outputCol := 0.U
        loadRow   := 0.U
        loadCol   := 0.U

        // 清空 buffer 和 tmpRow，避免上一轮残留
        for (r <- 0 until 5) {
          for (c <- 0 until 32) {
            buffer(r)(c) := 0.S
          }
        }

        for (c <- 0 until 32) {
          tmpRow(c) := 0.S
        }

        dbgLineHitCnt := 0.U
      }
    }

    // ------------------------------------------------------------
    // 从 DMA 加载前五行元素到 LineBuffer
    // ------------------------------------------------------------
    is(sPrime) {
      io.in.ready := true.B

      when(io.in.valid) {
        buffer(loadRow)(loadCol) := io.in.bits.asSInt

        when(loadRow === 4.U && loadCol === 31.U) {
          state := sActive
        }

        when(loadCol === 31.U) {
          loadCol := 0.U
          loadRow := loadRow + 1.U
        }.otherwise {
          loadCol := loadCol + 1.U
        }
      }
    }

    // ------------------------------------------------------------
    // 输出 32 行 x 36 列
    // outputCol = 0..35
    // 其中 outputCol 2..33 对应图像内部列 0..31
    // outputCol 0..1 和 34..35 为左右 padding / flush 区域
    // ------------------------------------------------------------
    is(sActive) {
      val bufCol  = (outputCol - 2.U)(4, 0)     // 映射到 buffer 列索引 0..31，截断至 5-bit
      val inImage = outputCol >= 2.U && outputCol <= 33.U

      // DMA 加载后续行进入 tmpRow
      // outputRow 2..28 时，预加载 input row = outputRow + 3
      // 注意：tmpRow 必须等整行加载完整后，才能并入 buffer
      val needLoad  = outputRow >= 2.U && outputRow + 3.U < 32.U
      val waitInput = needLoad && inImage && !io.in.valid
      val advance   = !io.stall && !waitInput
      
      when(outputRow>=28.U && outputCol>=30.U){
        printf("[TAIL-LB] row=%d col=%d colValid=%d\n",
          outputRow, outputCol, io.colValid)}

      // ------------------------------------------------------------
      // 列输出，含上下 padding
      // ------------------------------------------------------------
      when(inImage) {
        when(outputRow === 0.U) {
          // top 两行 padding
          io.colOut(0) := 0.S(16.W)
          io.colOut(1) := 0.S(16.W)
          io.colOut(2) := buffer(0)(bufCol)
          io.colOut(3) := buffer(1)(bufCol)
          io.colOut(4) := buffer(2)(bufCol)

        }.elsewhen(outputRow === 1.U) {
          // top 一行 padding
          io.colOut(0) := 0.S(16.W)
          io.colOut(1) := buffer(0)(bufCol)
          io.colOut(2) := buffer(1)(bufCol)
          io.colOut(3) := buffer(2)(bufCol)
          io.colOut(4) := buffer(3)(bufCol)

        }.elsewhen(outputRow === 30.U) {
          // bottom 一行 padding
          io.colOut(0) := buffer(1)(bufCol)
          io.colOut(1) := buffer(2)(bufCol)
          io.colOut(2) := buffer(3)(bufCol)
          io.colOut(3) := 0.S(16.W)
          io.colOut(4) := 0.S(16.W)

        }.elsewhen(outputRow === 31.U) {
          // bottom 两行 padding
          io.colOut(0) := buffer(2)(bufCol)
          io.colOut(1) := buffer(3)(bufCol)
          io.colOut(2) := buffer(4)(bufCol)
          io.colOut(3) := 0.S(16.W)
          io.colOut(4) := 0.S(16.W)

        }.otherwise {
          // 中间行 2..29：顺序读出 buffer[0..4]
          for (i <- 0 until 5) {
            io.colOut(i) := buffer(i)(bufCol)
          }
        }

        // 真正图像输出列 2..33：
        // 送入 ConvEngine，并且允许后续 store。
        io.colValid   := true.B
        io.storeValid := true.B

      }.otherwise {
        // 左/右 padding 列：输出全零
        io.colOut := VecInit.fill(5)(0.S(16.W))

        // 延长 colValid 到 34..35，让管线尾部能够 outValid 标记
        // 注意：这些 flush 列只用于推出 ConvUnit pipeline，不应写入最终 output。
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

      when(io.start) {
        dbgLineHitCnt := 0.U
      }
      
      when(outputRow >= 30.U){
        printf("[TAIL] row=%d col=%d inImage=%d colvalid=%d advance=%d   stall=%d\n",outputRow,outputCol,inImage,io.colValid,advance,io.stall)}

      // ------------------------------------------------------------
      // DMA 加载后续行进入 tmpRow
      // outputRow 2..28 时，预加载 input row = outputRow + 3
      // 注意：tmpRow 必须等整行加载完整后，才能并入 buffer
      // ------------------------------------------------------------
      io.in.ready := needLoad && inImage && !io.stall

      when(io.in.valid && io.in.ready) {
        tmpRow(loadCol) := io.in.bits.asSInt

        when(loadCol === 31.U) {
          loadCol := 0.U
        }.otherwise {
          loadCol := loadCol + 1.U
        }
      }

      // ------------------------------------------------------------
      // 计数器推进（反压中止）
      // 关键修复：
      // 原先 buffer 在 outputRow >= 2.U 时每拍都上移，tmpRow 尚未完整加载就进入 buffer(4)，
      // 会导致窗口错位/重复。
      //
      // 现在只在一整行输出结束时（outputCol === 35.U）上移一次，
      // 此时 tmpRow 已经完成本行加载，才安全并入 buffer(4)。
      // ------------------------------------------------------------
      when(advance) {
        when(outputCol === 35.U) {
          outputCol := 0.U
          outputRow := outputRow + 1.U

          // 行 2~3 及之后：buffer 上移一行，tmpRow 进入 buffer(4)
          // 只在行尾做一次，不能每个 cycle 做。
          when(outputRow >= 2.U && outputRow + 3.U < 32.U) {
            buffer(0) := buffer(1)
            buffer(1) := buffer(2)
            buffer(2) := buffer(3)
            buffer(3) := buffer(4)
            buffer(4) := tmpRow
          }

          when(outputRow === 31.U) {
            state := sDone
          }

        }.otherwise {
          outputCol := outputCol + 1.U
        }
      }
    }

    // ------------------------------------------------------------
    // start 下降后回到 sIdle
    // ------------------------------------------------------------
    is(sDone) {
      when(!io.start) {
        state := sIdle
      }
    }
  }
}
