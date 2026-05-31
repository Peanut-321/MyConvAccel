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

    // debug
    val state       = Output(UInt(3.W))
    val dbgInflight = Output(UInt(3.W))
    val dbgFifoCount = Output(UInt(4.W))

    val dbgBytesIssued = Output(UInt(16.W))
    val dbgElemsEmitted = Output(UInt(16.W))
    val dbgUnpackIdx = Output(UInt(2.W))
  })

  // DMA FSM states
  val sIdle :: sIssue :: sWaitResp :: sUnpack :: sGather :: sDone :: sError :: sLoadActive :: Nil = Enum(8)

  val state = RegInit(sIdle)
  io.state := state

  // command registers
  val opReg       = RegInit(0.U(2.W))
  val baseAddrReg = RegInit(0.U(64.W))
  val lengthReg   = RegInit(0.U(16.W))

  // load path counters
  val bytesIssued   = RegInit(0.U(16.W))
  val elemsEmitted  = RegInit(0.U(16.W))
  val unpackIdx     = RegInit(0.U(2.W))

  // only allow one in-flight memory request for easier debugging
  val inflightMax   = 1.U(3.W)
  val inflightCount = RegInit(0.U(3.W))

  // response FIFO, only for LOAD responses
  val respFifo = Module(new Queue(UInt(64.W), 8))

  // store path registers
  val packBuf      = RegInit(0.U(64.W))
  val packCnt      = RegInit(0.U(2.W))
  val bytesWritten = RegInit(0.U(16.W))

  // alignment check
  val nullAddr  = io.cmd.bits.baseAddr === 0.U
  val misalign8 = io.cmd.bits.baseAddr(2, 0) =/= 0.U
  val misalign2 = io.cmd.bits.baseAddr(0) =/= 0.U

  val alignErr =
    nullAddr ||
    ((io.cmd.bits.op === DmaOp.load_input || io.cmd.bits.op === DmaOp.store_output) && misalign8) ||
    ((io.cmd.bits.op === DmaOp.load_kernel) && misalign2)

  // op helper
  val isStoreOp = opReg === DmaOp.store_output

  // element queue: load response unpacked into 16-bit elements
  val elemQueue = Module(new Queue(UInt(16.W), 8))
  io.loadStream <> elemQueue.io.deq

  // default outputs
  io.busy := state === sLoadActive || state === sIssue || state === sWaitResp || state === sUnpack || state === sGather

  io.cmd.ready := false.B

  io.storeStream.ready := false.B

  io.mem.req.valid := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.isWrite := false.B
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.mask := "hFF".U

  /*
   * IMPORTANT FIX:
   * Load responses should enter respFifo.
   * Store responses are only ACK-like completion responses and must NOT enter respFifo.
   * Otherwise store_output fills respFifo to 8 and blocks mem.resp.ready.
   */
  io.mem.resp.ready := Mux(isStoreOp, true.B, respFifo.io.enq.ready)
  respFifo.io.enq.valid := io.mem.resp.valid && !isStoreOp
  respFifo.io.enq.bits := io.mem.resp.bits.data

  respFifo.io.deq.ready := false.B

  io.done := false.B
  io.error := false.B

  elemQueue.io.enq.valid := false.B
  elemQueue.io.enq.bits := 0.U

  // debug outputs
  io.dbgInflight := inflightCount
  io.dbgFifoCount := respFifo.io.count
  io.dbgBytesIssued := bytesIssued
  io.dbgElemsEmitted := elemsEmitted
  io.dbgUnpackIdx := unpackIdx

  // ------------------------------------------------------------
  // Concurrent LOAD engine:
  // When state === sLoadActive, issue memory load requests and unpack responses.
  // ------------------------------------------------------------
  val inLoadActive = state === sLoadActive

  when(inLoadActive) {
    val canIssue = bytesIssued < lengthReg && inflightCount === 0.U

    io.mem.req.valid := canIssue
    io.mem.req.bits.addr := baseAddrReg + bytesIssued
    io.mem.req.bits.isWrite := false.B
    io.mem.req.bits.tag := 0.U
    io.mem.req.bits.mask := "hFF".U

    when(io.mem.req.fire) {
      bytesIssued := bytesIssued + 8.U
    }

    // unpack one 64-bit word into four 16-bit elements
    val hasWord = respFifo.io.deq.valid
    val word = respFifo.io.deq.bits

    val elem = MuxLookup(unpackIdx, 0.U(16.W), Seq(
      0.U -> word(15, 0),
      1.U -> word(31, 16),
      2.U -> word(47, 32),
      3.U -> word(63, 48)
    ))

    elemQueue.io.enq.valid := hasWord
    elemQueue.io.enq.bits := elem

    val elemFired = elemQueue.io.enq.fire
    val isLastElem = unpackIdx === 3.U
    val isLastGlobal = (elemsEmitted + 1.U) === (lengthReg >> 1)

    respFifo.io.deq.ready := elemFired && (isLastElem || isLastGlobal)

    when(elemFired) {
      unpackIdx := unpackIdx + 1.U
      elemsEmitted := elemsEmitted + 1.U
    }

    val issueFired = io.mem.req.fire
    val unpackWordDone = elemFired && (isLastElem || isLastGlobal)

    inflightCount := inflightCount + issueFired - unpackWordDone

    val allIssued = bytesIssued >= lengthReg
    val fifoEmpty = !respFifo.io.deq.valid
    val allEmitted = elemsEmitted === (lengthReg >> 1)
    val queueDrained = !elemQueue.io.deq.valid

    when(allIssued && fifoEmpty && allEmitted && queueDrained) {
      state := sDone
    }

//    printf(p"[DMA-LOAD] state=${state} req.valid=${io.mem.req.valid} req.ready=${io.mem.req.ready} fire=${io.mem.req.fire} bytesIssued=${bytesIssued} length=${lengthReg} inflight=${inflightCount} fifo=${respFifo.io.count} elems=${elemsEmitted} unpackIdx=${unpackIdx}\n")
  }

  // ------------------------------------------------------------
  // Main FSM
  // ------------------------------------------------------------
  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        opReg := io.cmd.bits.op
        baseAddrReg := io.cmd.bits.baseAddr
        lengthReg := io.cmd.bits.length

        // clear counters
        bytesIssued := 0.U
        elemsEmitted := 0.U
        unpackIdx := 0.U
        packBuf := 0.U
        packCnt := 0.U
        bytesWritten := 0.U
        inflightCount := 0.U

        when(alignErr) {
          state := sError
        }.otherwise {
          io.cmd.ready := true.B

          when(io.cmd.bits.op === DmaOp.store_output) {
            state := sGather
          }.otherwise {
            state := sLoadActive
          }
        }
      }
    }

    // ----------------------------------------------------------
    // Store path: gather four 16-bit values into one 64-bit word.
    // ----------------------------------------------------------
    is(sGather) {
      io.storeStream.ready := true.B

      val nextPackBuf = Cat(io.storeStream.bits, packBuf(63, 16))

      when(io.storeStream.fire) {
        packBuf := nextPackBuf

        when(packCnt === 3.U) {
          packCnt := 0.U
          state := sIssue
        }.otherwise {
          packCnt := packCnt + 1.U
        }
      }

//      printf(p"[DMA-GATHER] state=${state} store.valid=${io.storeStream.valid} store.ready=${io.storeStream.ready} fire=${io.storeStream.fire} packCnt=${packCnt} bytesWritten=${bytesWritten} length=${lengthReg}\n")
    }

    // ----------------------------------------------------------
    // Issue one memory request.
    // For store_output, write one packed 64-bit word to memory.
    // ----------------------------------------------------------
    is(sIssue) {
      when(opReg === DmaOp.store_output) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := baseAddrReg + bytesWritten
        io.mem.req.bits.data := packBuf
        io.mem.req.bits.isWrite := true.B
        io.mem.req.bits.tag := 0.U
        io.mem.req.bits.mask := "hFF".U

//        printf(p"[DMA-ISSUE] state=${state} op=${opReg} req.valid=${io.mem.req.valid} req.ready=${io.mem.req.ready} fire=${io.mem.req.fire} addr=0x${Hexadecimal(io.mem.req.bits.addr)} data=0x${Hexadecimal(io.mem.req.bits.data)} bytesWritten=${bytesWritten} length=${lengthReg} fifo=${respFifo.io.count} resp.valid=${io.mem.resp.valid} resp.ready=${io.mem.resp.ready}\n")

        when(io.mem.req.fire) {
          bytesWritten := bytesWritten + 8.U

          when(bytesWritten + 8.U >= lengthReg) {
            state := sDone
          }.otherwise {
            state := sGather
          }
        }
      }
    }

    // Legacy wait response state, kept for compatibility.
    // The current load path mainly uses sLoadActive.
    is(sWaitResp) {
      when(respFifo.io.deq.valid) {
        unpackIdx := 0.U
        state := sUnpack
      }
    }

    // Legacy unpack state, kept for compatibility.
    is(sUnpack) {
      val elem = MuxLookup(unpackIdx, 0.U(16.W), Seq(
        0.U -> respFifo.io.deq.bits(15, 0),
        1.U -> respFifo.io.deq.bits(31, 16),
        2.U -> respFifo.io.deq.bits(47, 32),
        3.U -> respFifo.io.deq.bits(63, 48)
      ))

      elemQueue.io.enq.valid := true.B
      elemQueue.io.enq.bits := elem

      when(elemQueue.io.enq.fire) {
        unpackIdx := unpackIdx + 1.U
        elemsEmitted := elemsEmitted + 1.U

        when(unpackIdx === 3.U || (elemsEmitted + 1.U) === (lengthReg >> 1)) {
          respFifo.io.deq.ready := true.B

          when(bytesIssued >= lengthReg) {
            state := sDone
          }.otherwise {
            state := sIssue
          }
        }
      }
    }

    is(sDone) {
      io.done := true.B

      // accept next command while staying able to report done
      when(io.cmd.valid) {
        io.cmd.ready := true.B

        opReg := io.cmd.bits.op
        baseAddrReg := io.cmd.bits.baseAddr
        lengthReg := io.cmd.bits.length

        bytesIssued := 0.U
        elemsEmitted := 0.U
        unpackIdx := 0.U
        packBuf := 0.U
        packCnt := 0.U
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

//      printf(p"[DMA-DONE] state=${state} done=${io.done} fifo=${respFifo.io.count} bytesWritten=${bytesWritten} bytesIssued=${bytesIssued} elems=${elemsEmitted}\n")
    }

    is(sError) {
      io.error := true.B
      io.done := true.B

 //     printf(p"[DMA-ERROR] state=${state} alignErr=${alignErr} base=0x${Hexadecimal(baseAddrReg)} length=${lengthReg} op=${opReg}\n")
    }
  }
}
