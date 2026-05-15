package matrix

import chisel3._
import chisel3.util._

class ConvAccelTop extends Module {
  val io = IO(new Bundle {
    val start      = Input(Bool())
    val done       = Output(Bool())
    val kernelAddr = Input(UInt(64.W))
    val inputAddr  = Input(UInt(64.W))
    val outputAddr = Input(UInt(64.W))
    val mem        = new SimpleMemIO
    // debug
    val state        = Output(UInt(3.W))
    val dbgResultCnt = Output(UInt(11.W))
    val dbgQueueCnt  = Output(UInt(7.W))
    val dbgDmaDone   = Output(Bool())
    val dbgDmaState  = Output(UInt(3.W))
  })

  // ── 子模块 ──
  val dma        = Module(new ConvDMA)
  val lineBuf    = Module(new LineBuffer)
  val engine     = Module(new ConvEngine)
  val storeQueue = Module(new Queue(SInt(16.W), 2048))
  val inputQueue = Module(new Queue(UInt(16.W), 1024))

  // ── FSM 状态 ──
  val sIdle :: sLoadKernel :: sLoadInput :: sCompute :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)
  io.state := state

  // ── 地址锁存 ──
  val kernelAddrReg = RegInit(0.U(64.W))
  val inputAddrReg  = RegInit(0.U(64.W))
  val outputAddrReg = RegInit(0.U(64.W))

  // ── 计数器 ──
  val kernelElemCnt = RegInit(0.U(6.W))   // 0..31, 32 个 kernel 元素
  val resultCnt     = RegInit(0.U(11.W))  // 0..1023, 产出结果数
  io.dbgResultCnt  := resultCnt
  io.dbgQueueCnt   := storeQueue.io.count
  io.dbgDmaDone    := dma.io.done
  io.dbgDmaState   := dma.io.state

  // ── 阶段跳转条件 ──
  val goLoadKernel = state === sIdle       && io.start
  val goLoadInput  = state === sLoadKernel && dma.io.done
  val goCompute    = state === sLoadInput  && dma.io.done
  val goDone       = state === sCompute    && resultCnt >= 1088.U && dma.io.done

  // ── 状态寄存器更新 ──
  when (goLoadKernel) { state := sLoadKernel }
  when (goLoadInput)  { state := sLoadInput  }
  when (goCompute)    { state := sCompute    }
  when (goDone)       { state := sDone       }
  when (state === sDone && !io.start) { state := sIdle }

  // ── 地址锁存（sIdle 时采样）──
  when (state === sIdle && io.start) {
    kernelAddrReg := io.kernelAddr
    inputAddrReg  := io.inputAddr
    outputAddrReg := io.outputAddr
  }

  // ── DMA 命令 ──
  dma.io.cmd.valid := goLoadKernel || goLoadInput || goCompute
  dma.io.cmd.bits.op       := Mux(goCompute, DmaOp.store_output,
                                Mux(goLoadKernel, DmaOp.load_kernel, DmaOp.load_input))
  dma.io.cmd.bits.baseAddr := Mux(goLoadKernel, io.kernelAddr,
                                Mux(goLoadInput,  inputAddrReg, outputAddrReg))
  dma.io.cmd.bits.length   := Mux(goLoadKernel, 64.U, Mux(goCompute, 2176.U, 2048.U))

  // ── io.mem 直连 DMA ──
  io.mem <> dma.io.mem

  // ── DMA loadStream 扇出 ──
  // sLoadKernel: 写 ConvEngine kernel ROM
  // sLoadInput:  全部进 inputQueue

  // Defaults
  dma.io.loadStream.ready := false.B
  engine.io.kernelWe   := false.B
  engine.io.kernelAddr := 0.U
  engine.io.kernelData := 0.S
  inputQueue.io.enq.valid := false.B
  inputQueue.io.enq.bits  := 0.U

  // sLoadKernel: 消费 32 个 kernel 元素
  when (state === sLoadKernel) {
    dma.io.loadStream.ready := kernelElemCnt < 32.U
    when (dma.io.loadStream.fire) {
      kernelElemCnt := kernelElemCnt + 1.U
    }
    engine.io.kernelWe   := dma.io.loadStream.fire && kernelElemCnt < 25.U
    engine.io.kernelAddr := kernelElemCnt
    engine.io.kernelData := dma.io.loadStream.bits.asSInt
  }

  // sLoadInput: DMA → inputQueue
  when (state === sLoadInput) {
    dma.io.loadStream.ready  := inputQueue.io.enq.ready
    inputQueue.io.enq.valid  := dma.io.loadStream.valid
    inputQueue.io.enq.bits   := dma.io.loadStream.bits
  }

  // ── LineBuffer start 脉冲（sLoadInput 入口）──
  val lineBufStartReg = RegInit(false.B)
  when (goLoadInput) {
    lineBufStartReg := true.B
  }.otherwise {
    lineBufStartReg := false.B
  }
  lineBuf.io.start := lineBufStartReg

  // ── inputQueue → LineBuffer（sLoadInput + sCompute 期间）──
  val feedLineBuf = state === sLoadInput || state === sCompute
  inputQueue.io.deq.ready := feedLineBuf && lineBuf.io.in.ready
  lineBuf.io.in.valid     := feedLineBuf && inputQueue.io.deq.valid
  lineBuf.io.in.bits      := inputQueue.io.deq.bits

  // ── 计算通路 ──
  engine.io.colIn    := lineBuf.io.colOut
  engine.io.colValid := lineBuf.io.colValid && !engine.io.stall

  // ── 结果入 storeQueue ──
  storeQueue.io.enq.valid := engine.io.outValid
  storeQueue.io.enq.bits  := engine.io.result

  // ── storeQueue → DMA storeStream ──
  dma.io.storeStream.valid := storeQueue.io.deq.valid
  dma.io.storeStream.bits  := storeQueue.io.deq.bits.asUInt
  storeQueue.io.deq.ready  := dma.io.storeStream.ready

  // ── stall：LineBuffer 在 sLoadInput/sCompute 期间运行；Engine 也在此期间运行（窗口对齐需要）──
  // storeQueue 满时反压整个管线
  lineBuf.io.stall := state =/= sCompute && state =/= sLoadInput || !storeQueue.io.enq.ready
  engine.io.stall  := state =/= sCompute && state =/= sLoadInput || !storeQueue.io.enq.ready

  // ── 结果计数 ──
  when (engine.io.outValid && storeQueue.io.enq.fire) {
    resultCnt := resultCnt + 1.U
  }

  // ── done ──
  io.done := state === sDone

  // ── 计数器复位 ──
  when (state === sIdle) {
    kernelElemCnt := 0.U
    resultCnt     := 0.U

  }
}
