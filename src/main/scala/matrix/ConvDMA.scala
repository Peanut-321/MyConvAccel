package matrix

import chisel3._
import chisel3.util._

class ConvDMA extends Module {
  val io = IO(new Bundle {
    val cmd         = Flipped(Decoupled(new DmaCmd))
    val loadStream  = Decoupled(UInt(16.W))
    val storeStream = Flipped(Decoupled(UInt(16.W)))
    val mem         = new SimpleMemIO
    val busy        = Output(Bool())
    val done        = Output(Bool())
    val error       = Output(Bool())
    val state         = Output(UInt(3.W))   // 调试：FSM 状态值
    val dbgInflight   = Output(UInt(3.W))   // 调试：inflightCount
    val dbgFifoCount  = Output(UInt(4.W))   // 调试：respFifo count
  })

  //定义reading FSM状态
  val sIdle :: sIssue :: sWaitResp :: sUnpack :: sGather :: sDone :: sError :: sLoadActive :: Nil = Enum(8) 
  val state = RegInit(sIdle) //定义初始状态
  io.state := state          // 调试：暴露 FSM 状态

  //定义锁存寄存器
  val opReg       = RegInit(0.U(2.W))                           
  val baseAddrReg = RegInit(0.U(64.W))                                            
  val lengthReg   = RegInit(0.U(16.W))
  
  //定义计数器
  val bytesIssued  = RegInit(0.U(16.W)) //记录发出了多少个字节
  val elemsEmitted = RegInit(0.U(16.W)) //记录推了多少个元素
  val unpackIdx    = RegInit(0.U(2.W))  //记录拆到第几个元素，只有0，1，2，3
  val inflightMax    = 4.U(3.W)
  val inflightCount = RegInit(0.U(3.W)) //已发请求但未拆完的word数，流控用

  //定义响应锁存（锁存数据）
  val respFifo = Module(new Queue(UInt(64.W), 8))
  io.dbgInflight  := inflightCount
  io.dbgFifoCount := respFifo.io.count

  // store 路径寄存器                                                             
  val packBuf      = RegInit(0.U(64.W))  // shift register 拼包                   
  val packCnt      = RegInit(0.U(2.W))   // 已收元素计数 0..3                     
  val bytesWritten = RegInit(0.U(16.W))  // 已写字节数                            
  //对齐检查
  val nullAddr   = io.cmd.bits.baseAddr === 0.U         // 地址为 0
  val misalign8  = io.cmd.bits.baseAddr(2, 0) =/= 0.U   // 低 3 位非 0 → 非 8字节对齐
  val misalign2  = io.cmd.bits.baseAddr(0) =/= 0.U      // bit0 非 0 → 非 2字节对齐

  val alignErr = nullAddr || 
    ((io.cmd.bits.op === DmaOp.load_input || io.cmd.bits.op ===
    DmaOp.store_output) && misalign8) ||
    (io.cmd.bits.op === DmaOp.load_kernel && misalign2)

  // 元素缓冲队列：DMA 往 enq 推拆好的 16-bit 元素，外界通过 deq (→ io.loadStream) 取走
  // 深度 8，缓冲 2 个 64-bit word 的反压窗口。外部 consumer 临时忙不过来时，元素暂存在队列里，不丢数据。
  val elemQueue = Module(new Queue(UInt(16.W), 8))
  io.loadStream <> elemQueue.io.deq    // 批量连线：deq.valid/bit→loadStream, loadStream.ready→deq.ready

  io.busy := state === sLoadActive || state === sIssue || state === sWaitResp || state === sUnpack || state === sGather

  io.cmd.ready              := false.B   
  io.storeStream.ready      := false.B                                            
  io.mem.req.valid          := false.B           
  io.mem.req.bits.addr      := 0.U                                                
  io.mem.req.bits.data      := 0.U                                                
  io.mem.req.bits.isWrite   := false.B                                            
  io.mem.req.bits.tag       := 0.U                                                
  io.mem.req.bits.mask      := 0xFF.U                                             
  io.mem.resp.ready         := respFifo.io.enq.ready
  respFifo.io.enq.valid     := io.mem.resp.valid
  respFifo.io.enq.bits      := io.mem.resp.bits.data
  respFifo.io.deq.ready     := false.B
  io.done                   := false.B                                            
  io.error                  := false.B                                            
  elemQueue.io.enq.valid    := false.B                                            
  elemQueue.io.enq.bits     := 0.U

  // ── 并发 Load 引擎（sLoadActive 时 issue ∥ unpack 同时运行）──
  val inLoadActive = state === sLoadActive
  when (inLoadActive) {
    // Issue 引擎：流控发射读请求
    val canIssue = bytesIssued < lengthReg && inflightCount < inflightMax
    io.mem.req.valid       := canIssue
    io.mem.req.bits.addr   := baseAddrReg + bytesIssued
    io.mem.req.bits.isWrite := false.B
    io.mem.req.bits.tag    := 0.U
    io.mem.req.bits.mask   := 0xFF.U

    when (io.mem.req.fire) {
      bytesIssued := bytesIssued + 8.U
    }

    // Unpack 引擎：从 FIFO 取 word 拆成 4 个 16-bit 元素
    val hasWord = respFifo.io.deq.valid
    val word    = respFifo.io.deq.bits
    val elem    = MuxLookup(unpackIdx, 0.U(16.W))(Seq(
      0.U -> word(15, 0),
      1.U -> word(31, 16),
      2.U -> word(47, 32),
      3.U -> word(63, 48)
    ))

    elemQueue.io.enq.valid := hasWord
    elemQueue.io.enq.bits  := elem

    val elemFired     = elemQueue.io.enq.fire
    val isLastElem    = unpackIdx === 3.U
    val isLastGlobal  = (elemsEmitted + 1.U) === (lengthReg >> 1)

    respFifo.io.deq.ready := elemFired && (isLastElem || isLastGlobal)

    when (elemFired) {
      unpackIdx    := unpackIdx + 1.U
      elemsEmitted := elemsEmitted + 1.U
    }

    // inflightCount 净变化：发射 +1，word 拆完 -1，同拍发生则抵消
    val issueFired     = io.mem.req.fire
    val unpackWordDone = elemFired && (isLastElem || isLastGlobal)
    inflightCount := inflightCount + issueFired - unpackWordDone
  }

  switch(state){
    is(sIdle){
      when(io.cmd.valid){
        opReg       := io.cmd.bits.op
        baseAddrReg := io.cmd.bits.baseAddr
        lengthReg   := io.cmd.bits.length

        // 清计数器                                                                 
        bytesIssued  := 0.U                                                          
        elemsEmitted := 0.U                                                         
        unpackIdx    := 0.U  
        packBuf      := 0.U                                                             
        packCnt      := 0.U                                                             
        bytesWritten := 0.U
        inflightCount := 0.U

        //判断地址是否对齐
        when(alignErr){
          state := sError
        }.otherwise{
          io.cmd.ready := true.B
          //进行读写分流，走不同的路径
          when(io.cmd.bits.op === DmaOp.store_output){
            state := sGather
          }.otherwise{
            state := sLoadActive
          }
        }
      }
    }

    //发读/写请求
    is(sIssue){
      //写请求
      when(opReg === DmaOp.store_output){
        io.mem.req.valid        := true.B
        io.mem.req.bits.addr    := baseAddrReg + bytesWritten
        io.mem.req.bits.data    := packBuf
        io.mem.req.bits.isWrite := true.B

        when(io.mem.req.fire){
          bytesWritten := bytesWritten + 8.U

          when(bytesWritten + 8.U >= lengthReg){
            state := sDone
          }.otherwise{
            state := sGather //回去收下一组
          }
        }.otherwise{
          io.mem.req.valid := true.B
        }
      }
    }
    
    //等内存响应
    is(sWaitResp){
      when(respFifo.io.deq.valid){
        unpackIdx := 0.U
        state := sUnpack
      }
    }

    //拆包，将respWord按unpackIdx拆成四个元素
    is(sUnpack){
        val elem = MuxLookup(unpackIdx, 0.U(16.W))(Seq(
          0.U -> respFifo.io.deq.bits(15, 0),
          1.U -> respFifo.io.deq.bits(31, 16),
          2.U -> respFifo.io.deq.bits(47, 32),
          3.U -> respFifo.io.deq.bits(63, 48)
        ))

        elemQueue.io.enq.valid := true.B  //有元素要推
        elemQueue.io.enq.bits  := elem  //推入元素

        //队列接收元素
        when(elemQueue.io.enq.fire){
          unpackIdx := unpackIdx + 1.U  //自动回卷 0->1->2->3->0
          elemsEmitted := elemsEmitted + 1.U //记录推了多少个元素

          //判断当前cycle的元素是不是当前word最后一个（或全局最后一个）
          when(unpackIdx === 3.U || (elemsEmitted + 1.U) === (lengthReg >> 1)){
            respFifo.io.deq.ready := true.B
            //全局结束
            when(bytesIssued >= lengthReg){
              state := sDone
            
            //当前结束
            }.otherwise{
              state := sIssue
            }
          }
        }
    }

    is(sLoadActive){
      val allIssued  = bytesIssued >= lengthReg
      val fifoEmpty  = !respFifo.io.deq.valid
      val allEmitted = elemsEmitted === (lengthReg >> 1)
      val queueDrained = !elemQueue.io.deq.valid  // 消费端已取走全部元素
      when (allIssued && fifoEmpty && allEmitted && queueDrained) {
        state := sDone
      }
    }

    is(sGather){
      io.storeStream.ready := true.B

      //cat是位拼接，相当于verilog的{a, b}，新元素塞地位，旧元素往高位移16位
      val nextPackBuf = Cat(io.storeStream.bits, packBuf(63,16))

      when(io.storeStream.fire){
        packBuf := nextPackBuf
        packCnt := packCnt + 1.U

        //攒够4个
        when(packCnt === 3.U){
          state := sIssue
        }
      }
    }

    is(sDone){
      io.done := true.B
      // 接受背靠背传输命令（TC-PIPE-03）
      when(io.cmd.valid) {
        io.cmd.ready := true.B
        opReg       := io.cmd.bits.op
        baseAddrReg := io.cmd.bits.baseAddr
        lengthReg   := io.cmd.bits.length
        bytesIssued  := 0.U
        elemsEmitted := 0.U
        unpackIdx    := 0.U
        packBuf      := 0.U
        packCnt      := 0.U
        bytesWritten := 0.U
        inflightCount := 0.U
        when(alignErr) {
          state := sError
        }.otherwise {
          when(io.cmd.bits.op === DmaOp.store_output) {
            state := sGather
          }.otherwise {
            state := sLoadActive
          }
        }
      }
    }

    is(sError){
      io.error := true.B
      io.done  := true.B
    }
  }
}


