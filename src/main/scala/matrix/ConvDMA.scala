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
    val state       = Output(UInt(3.W))   // 调试端口：FSM 状态值
  })

  //定义reading FSM状态
  val sIdle :: sIssue :: sWaitResp :: sUnpack :: sGather :: sDone :: sError :: Nil = Enum(7) 
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

  //定义响应锁存（锁存数据）
  val respWord = RegInit(0.U(64.W))

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

  io.busy := state === sIssue || state === sWaitResp || state === sUnpack || state === sGather

  io.cmd.ready              := false.B   
  io.storeStream.ready      := false.B                                            
  io.mem.req.valid          := false.B           
  io.mem.req.bits.addr      := 0.U                                                
  io.mem.req.bits.data      := 0.U                                                
  io.mem.req.bits.isWrite   := false.B                                            
  io.mem.req.bits.tag       := 0.U                                                
  io.mem.req.bits.mask      := 0xFF.U                                             
  io.mem.resp.ready         := false.B                                            
  io.done                   := false.B                                            
  io.error                  := false.B                                            
  elemQueue.io.enq.valid    := false.B                                            
  elemQueue.io.enq.bits     := 0.U  


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

        //判断地址是否对齐
        when(alignErr){
          state := sError
        }.otherwise{
          io.cmd.ready := true.B
          //进行读写分流，走不同的路径
          when(io.cmd.bits.op === DmaOp.store_output){
            state := sGather
          }.otherwise{
            state := sIssue
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
      }.otherwise{
        //读请求
        when(bytesIssued < lengthReg){

          io.mem.req.valid       := true.B                                                
          io.mem.req.bits.addr   := baseAddrReg + bytesIssued                             
          io.mem.req.bits.isWrite := false.B                                              
          io.mem.req.bits.tag    := 0.U                                                   
          io.mem.req.bits.mask   := 0xFF.U 

          when(io.mem.req.fire){
            bytesIssued := bytesIssued + 8.U
            state := sWaitResp
          }.otherwise{
            io.mem.req.valid := true.B
          }
        }.otherwise{
          state := sDone
        }
      }  
    }
    
    //等内存响应
    is(sWaitResp){
      when(io.mem.resp.valid){
        respWord := io.mem.resp.bits.data
        unpackIdx := 0.U
        io.mem.resp.ready := true.B
        state := sUnpack
      }
    }

    //拆包，将respWord按unpackIdx拆成四个元素
    is(sUnpack){
        val elem = MuxLookup(unpackIdx, 0.U(16.W))(Seq(
          0.U -> respWord(15, 0),
          1.U -> respWord(31, 16),
          2.U -> respWord(47, 32),
          3.U -> respWord(63, 48)
        ))

        elemQueue.io.enq.valid := true.B  //有元素要推
        elemQueue.io.enq.bits  := elem  //推入元素

        //队列接收元素
        when(elemQueue.io.enq.fire){
          unpackIdx := unpackIdx + 1.U  //自动回卷 0->1->2->3->0
          elemsEmitted := elemsEmitted + 1.U //记录推了多少个元素

          //判断当前cycle的元素是不是当前word最后一个（或全局最后一个）
          when(unpackIdx === 3.U || (elemsEmitted + 1.U) === (lengthReg >> 1)){
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
    }

    is(sError){
      io.error := true.B
      io.done  := true.B
    }
  }
}


