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

    val dbgKernelCnt    = Output(UInt(6.W))
    val dbgDmaInflight  = Output(UInt(3.W))
    val dbgDmaFifoCount = Output(UInt(4.W))

    val dbgBytesIssued  = Output(UInt(16.W))
    val dbgElemsEmitted = Output(UInt(16.W))
    val dbgUnpackIdx    = Output(UInt(2.W))
  })

  // ------------------------------------------------------------
  // Submodules
  // ------------------------------------------------------------
  val dma        = Module(new ConvDMA)
  val lineBuf    = Module(new LineBuffer)
  val engine     = Module(new ConvEngine)
  val storeQueue = Module(new Queue(UInt(16.W), 2048))
  val inputQueue = Module(new Queue(UInt(16.W), 1024))

  // ------------------------------------------------------------
  // FSM states
  // ------------------------------------------------------------
  val sIdle :: sLoadKernel :: sLoadInput :: sCompute :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  io.state := state

  // ------------------------------------------------------------
  // Address registers
  // ------------------------------------------------------------
  val kernelAddrReg = RegInit(0.U(64.W))
  val inputAddrReg  = RegInit(0.U(64.W))
  val outputAddrReg = RegInit(0.U(64.W))

  // ------------------------------------------------------------
  // Counters
  // ------------------------------------------------------------
  val kernelElemCnt = RegInit(0.U(6.W))   // 0..31, 32 kernel elements
  val resultCnt     = RegInit(0.U(11.W))  // output/result element count

  // ------------------------------------------------------------
  // IMPORTANT FIX:
  // Latch top-level done.
  // Do not expose done as only "state === sDone", because RoCC/C poll can miss
  // a one-cycle done pulse when Top immediately returns to sIdle.
  // ------------------------------------------------------------
  val doneReg = RegInit(false.B)

  // ------------------------------------------------------------
  // Debug outputs
  // ------------------------------------------------------------
  io.dbgResultCnt := resultCnt
  io.dbgQueueCnt  := storeQueue.io.count
  io.dbgDmaDone   := dma.io.done
  io.dbgDmaState  := dma.io.state

  io.dbgKernelCnt    := kernelElemCnt
  io.dbgDmaInflight  := dma.io.dbgInflight
  io.dbgDmaFifoCount := dma.io.dbgFifoCount

  io.dbgBytesIssued  := dma.io.dbgBytesIssued
  io.dbgElemsEmitted := dma.io.dbgElemsEmitted
  io.dbgUnpackIdx    := dma.io.dbgUnpackIdx

  // ------------------------------------------------------------
  // Stage transition conditions
  // ------------------------------------------------------------
  val goLoadKernel = state === sIdle       && io.start
  val goLoadInput  = state === sLoadKernel && dma.io.done
  val goCompute    = state === sLoadInput  && dma.io.done
  val goDone       = state === sCompute    && resultCnt >= 1024.U //&& dma.io.done

  // ------------------------------------------------------------
  // FSM update
  // ------------------------------------------------------------
  when(goLoadKernel) {
    state := sLoadKernel
  }

  when(goLoadInput) {
    state := sLoadInput
  }

  when(goCompute) {
    state := sCompute
  }

  when(goDone) { printf("[GO-DONE]\n")}
  when(goDone) {
    state := sDone
    doneReg := true.B
  }

  // After done is latched, Top may return to idle.
  // doneReg stays true until the next start.
  when(state === sDone && !io.start) {
    state := sIdle
  }

  // Clear latched done only when a new operation starts.
  when(state === sIdle && io.start) {
    doneReg := false.B
  }
  
  when(state === sIdle) {
  printf("[STATE] IDLE\n")
}

when(state === sLoadKernel) {
  printf("[STATE] LOAD_KERNEL dmaDone=%d\n", dma.io.done)
}

when(state === sLoadInput) {
  printf("[STATE] LOAD_INPUT dmaDone=%d\n", dma.io.done)
}

when(state === sCompute) {
  printf("[STATE] COMPUTE resultCnt=%d\n", resultCnt)
}

when(state === sDone) {
  printf("[STATE] DONE\n")
}


  // ------------------------------------------------------------
  // Latch addresses at start
  // ------------------------------------------------------------
  when(state === sIdle && io.start) {
    kernelAddrReg := io.kernelAddr
    inputAddrReg  := io.inputAddr
    outputAddrReg := io.outputAddr
  }

  // ------------------------------------------------------------
  // DMA command generation
  // ------------------------------------------------------------
  dma.io.cmd.valid := goLoadKernel || goLoadInput || goCompute

  dma.io.cmd.bits.op := Mux(
    goCompute,
    DmaOp.store_output,
    Mux(goLoadKernel, DmaOp.load_kernel, DmaOp.load_input)
  )

  dma.io.cmd.bits.baseAddr := Mux(
    goLoadKernel,
    //kernelAddrReg,
    io.kernelAddr,
    Mux(goLoadInput, inputAddrReg, outputAddrReg)
  )

  dma.io.cmd.bits.length := Mux(
    goLoadKernel,
    64.U,
    Mux(goCompute, 2176.U, 2048.U)
  )

  // ------------------------------------------------------------
  // Memory directly connected to DMA
  // ------------------------------------------------------------
  io.mem <> dma.io.mem

  // ------------------------------------------------------------
  // Defaults
  // ------------------------------------------------------------
  dma.io.loadStream.ready := false.B

  engine.io.kernelWe   := false.B
  engine.io.kernelAddr := 0.U
  engine.io.kernelData := 0.S

  inputQueue.io.enq.valid := false.B
  inputQueue.io.enq.bits  := 0.U

  // ------------------------------------------------------------
  // sLoadKernel:
  // Consume 32 kernel elements from DMA loadStream.
  // Only first 25 are written into ConvEngine kernel ROM.
  // ------------------------------------------------------------
  when(state === sLoadKernel) {
    dma.io.loadStream.ready := kernelElemCnt < 32.U

    when(dma.io.loadStream.fire) {
//      printf("[KERNEL-LOAD] idx=%d data=%d\n",
//              kernelElemCnt,
//              dma.io.loadStream.bits
//              )
      kernelElemCnt := kernelElemCnt + 1.U
    }

    engine.io.kernelWe   := dma.io.loadStream.fire && kernelElemCnt < 25.U
    engine.io.kernelAddr := kernelElemCnt
    engine.io.kernelData := dma.io.loadStream.bits.asSInt
  }

  // ------------------------------------------------------------
  // sLoadInput:
  // DMA loadStream -> inputQueue
  // ------------------------------------------------------------
  when(state === sLoadInput) {
    dma.io.loadStream.ready := inputQueue.io.enq.ready

    inputQueue.io.enq.valid := dma.io.loadStream.valid
    inputQueue.io.enq.bits  := dma.io.loadStream.bits
  }

  // ------------------------------------------------------------
  // LineBuffer start pulse when entering sLoadInput
  // ------------------------------------------------------------
  val lineBufStartReg = RegInit(false.B)

  when(goLoadInput) {
    lineBufStartReg := true.B
  }.otherwise {
    lineBufStartReg := false.B
  }

  lineBuf.io.start := lineBufStartReg

  // ------------------------------------------------------------
  // inputQueue -> LineBuffer
  // During sLoadInput and sCompute, feed input into line buffer.
  // ------------------------------------------------------------
  val feedLineBuf = state === sLoadInput || state === sCompute

  inputQueue.io.deq.ready := feedLineBuf && lineBuf.io.in.ready
  lineBuf.io.in.valid     := feedLineBuf && inputQueue.io.deq.valid
  lineBuf.io.in.bits      := inputQueue.io.deq.bits
  
  val dbgInputPrintCnt = RegInit(0.U(6.W))

when(inputQueue.io.deq.fire && dbgInputPrintCnt < 32.U) {
//  printf("[INPUT-FEED] ")
//  printf("cnt=%d ", dbgInputPrintCnt)
//  printf("data=%d ", inputQueue.io.deq.bits)
//  printf("lineReady=%d\n", lineBuf.io.in.ready)

  dbgInputPrintCnt := dbgInputPrintCnt + 1.U
}

when(state === sIdle && io.start) {
  dbgInputPrintCnt := 0.U
}


  // ------------------------------------------------------------
  // Compute path
  // ------------------------------------------------------------
  engine.io.colIn    := lineBuf.io.colOut
  engine.io.colValid := lineBuf.io.colValid && !engine.io.stall
  
  val storeValidD1 = RegNext(lineBuf.io.storeValid, false.B)
  val storeValidD2 = RegNext(storeValidD1, false.B)
  
  val dbgColPrintCnt = RegInit(0.U(6.W))
  
  when(lineBuf.io.colValid){printf("[LB-VALID]\n")}

when(lineBuf.io.colValid && dbgColPrintCnt < 32.U) {
//  printf("[LINEBUF-OUT] ")
//  printf("cnt=%d ", dbgColPrintCnt)
//  printf("colValid=%d ", lineBuf.io.colValid)
//  printf("engineStall=%d\n", engine.io.stall)

  dbgColPrintCnt := dbgColPrintCnt + 1.U
}
 
 when(state === sIdle && io.start) {
  dbgColPrintCnt := 0.U
}

  // ------------------------------------------------------------
  // Engine result -> storeQueue
  // ------------------------------------------------------------

  val dbgFirstOutCnt = RegInit(0.U(4.W))
  val storeColCnt = RegInit(0.U(6.W))
  
  when(state === sLoadKernel){storeColCnt := 0.U}
  
  val isRealStoreCol = storeColCnt >= 2.U
  
  storeQueue.io.enq.valid := engine.io.outValid && isRealStoreCol
  storeQueue.io.enq.bits  := engine.io.result.asUInt
  
  when(engine.io.outValid){
   when(storeColCnt === 33.U){storeColCnt := 0.U
     }.otherwise{storeColCnt := storeColCnt + 1.U}
  }
  
  when(engine.io.outValid && engine.io.result =/= 0.S){
        printf("[TOP-NZ]result=%d colValid=%d storeValid=%d storeValidD1=%d storeValidD2=%d\n",
          engine.io.result,
          lineBuf.io.colValid,
          lineBuf.io.storeValid,
          storeValidD1,
          storeValidD2
          )
  }
  
  
  
  
  val dbgStoreIdx = RegInit(0.U(12.W))
  
  when(state === sLoadKernel){
    dbgStoreIdx := 0.U
  }
  
  when(storeQueue.io.enq.fire){
    when(storeQueue.io.enq.bits =/= 0.U){
      printf("[STORE-HIT] idx=%d data=%d\n",
        dbgStoreIdx,
        storeQueue.io.enq.bits)
    }
     dbgStoreIdx :=  dbgStoreIdx + 1.U
  }
  
  val dbgOutPrintCnt = RegInit(0.U(6.W))
  
  when(storeQueue.io.enq.valid && storeQueue.io.enq.ready && dbgOutPrintCnt < 32.U){
//    printf("[ENGINE-OUT] cnt=%d, result=%d, storeReady=%d, resultCnt=%d\n",
//        dbgOutPrintCnt,
//        engine.io.result,
//        storeQueue.io.enq.ready,
//        resultCnt
 //       )
        dbgOutPrintCnt := dbgOutPrintCnt + 1.U
    }
    
    
val dbgStoreEnqHitCnt = RegInit(0.U(6.W))

when(storeQueue.io.enq.fire && storeQueue.io.enq.bits =/= 0.U && dbgStoreEnqHitCnt < 20.U) {
  printf("[STORE-ENQ-HIT] ")
  printf("hit=%d ", dbgStoreEnqHitCnt)
  printf("resultCnt=%d ", resultCnt)
  printf("engineResult=%d ", engine.io.result)
  printf("enqBits=%d\n", storeQueue.io.enq.bits)
  dbgStoreEnqHitCnt := dbgStoreEnqHitCnt + 1.U
}
when(state === sIdle && io.start) {
  dbgStoreEnqHitCnt := 0.U
}

when(state === sCompute && resultCnt < 20.U) {
  printf("[TOP-PROG] resultCnt=%d dmaDone=%d storeReady=%d engineValid=%d enqFire=%d\n",
    resultCnt,
    dma.io.done,
    storeQueue.io.enq.ready,
    engine.io.outValid,
    storeQueue.io.enq.fire
  )
}


  // ------------------------------------------------------------
  // storeQueue -> DMA storeStream
  // ------------------------------------------------------------
  dma.io.storeStream.valid := storeQueue.io.deq.valid
  dma.io.storeStream.bits  := storeQueue.io.deq.bits.asUInt
  storeQueue.io.deq.ready  := dma.io.storeStream.ready
  
  val dbgStoreDeqHitCnt = RegInit(0.U(6.W))

when(dma.io.storeStream.fire && dma.io.storeStream.bits =/= 0.U && dbgStoreDeqHitCnt < 20.U) {
  printf("[STORE-DEQ-HIT] ")
  printf("hit=%d ", dbgStoreDeqHitCnt)
  printf("storeBits=%d\n", dma.io.storeStream.bits)

  dbgStoreDeqHitCnt := dbgStoreDeqHitCnt + 1.U
}

when(state === sIdle && io.start) {
  dbgStoreDeqHitCnt := 0.U
}


  // ------------------------------------------------------------
  // Backpressure / stall
  // LineBuffer and Engine run during sLoadInput / sCompute.
  // If storeQueue is full, stall the pipeline.
  // ------------------------------------------------------------
  lineBuf.io.stall := state =/= sCompute && state =/= sLoadInput || !storeQueue.io.enq.ready
  engine.io.stall  := state =/= sCompute && state =/= sLoadInput || !storeQueue.io.enq.ready

  // ------------------------------------------------------------
  // Count generated results
  // ------------------------------------------------------------
  when(engine.io.outValid && storeQueue.io.enq.fire) {
    resultCnt := resultCnt + 1.U
    
    when(resultCnt(5,0) === 0.U){printf("[RCNT] resultCnt=%d\n", resultCnt)}
  }

  // ------------------------------------------------------------
  // Top-level done output
  // This is now latched, not a one-cycle state pulse.
  // ------------------------------------------------------------
  io.done := doneReg

  // ------------------------------------------------------------
  // Counter reset when idle
  // ------------------------------------------------------------
  when(state === sIdle) {
    kernelElemCnt := 0.U
    resultCnt     := 0.U
  }
}
