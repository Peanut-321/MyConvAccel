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

    val state       = Output(UInt(3.W))
    val dbgInflight = Output(UInt(3.W))
    val dbgFifoCount = Output(UInt(4.W))

    val dbgBytesIssued = Output(UInt(16.W))
    val dbgElemsEmitted = Output(UInt(16.W))
    val dbgUnpackIdx = Output(UInt(2.W))
  })

  val sIdle :: sIssue :: sWaitResp :: sUnpack :: sGather :: sDone :: sError :: sLoadActive :: Nil = Enum(8)

  val state = RegInit(sIdle)
  io.state := state

  val opReg       = RegInit(0.U(2.W))
  val baseAddrReg = RegInit(0.U(64.W))
  val lengthReg   = RegInit(0.U(16.W))

  val bytesIssued   = RegInit(0.U(16.W))
  val elemsEmitted  = RegInit(0.U(16.W))
  val unpackIdx     = RegInit(0.U(2.W))

  val inflightMax   = 1.U(3.W)
  val inflightCount = RegInit(0.U(3.W))

  val respFifo = Module(new Queue(UInt(64.W), 8))

  val packBuf      = RegInit(0.U(64.W))
  val packCnt      = RegInit(0.U(2.W))
  val bytesWritten = RegInit(0.U(16.W))

  val nullAddr  = io.cmd.bits.baseAddr === 0.U
  val misalign8 = io.cmd.bits.baseAddr(2, 0) =/= 0.U
  val misalign2 = io.cmd.bits.baseAddr(0) =/= 0.U

  val alignErr =
    nullAddr ||
    ((io.cmd.bits.op === DmaOp.load_input || io.cmd.bits.op === DmaOp.store_output) && misalign8) ||
    ((io.cmd.bits.op === DmaOp.load_kernel) && misalign2)

  val isStoreOp = opReg === DmaOp.store_output

  val elemQueue = Module(new Queue(UInt(16.W), 8))
  io.loadStream <> elemQueue.io.deq

  io.busy := state === sLoadActive || state === sIssue || state === sWaitResp || state === sUnpack || state === sGather

  io.cmd.ready := false.B
  io.storeStream.ready := false.B

  io.mem.req.valid := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.isWrite := false.B
  io.mem.req.bits.tag := 0.U
  io.mem.req.bits.mask := "hFF".U

  io.mem.resp.ready := Mux(isStoreOp, true.B, respFifo.io.enq.ready)
  respFifo.io.enq.valid := io.mem.resp.valid && !isStoreOp
  respFifo.io.enq.bits := io.mem.resp.bits.data

  respFifo.io.deq.ready := false.B

  io.done := false.B
  io.error := false.B

  elemQueue.io.enq.valid := false.B
  elemQueue.io.enq.bits := 0.U

  io.dbgInflight := inflightCount
  io.dbgFifoCount := respFifo.io.count
  io.dbgBytesIssued := bytesIssued
  io.dbgElemsEmitted := elemsEmitted
  io.dbgUnpackIdx := unpackIdx

  // Load engine (sLoadActive)
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
  }

  // Main FSM
  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
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
          io.cmd.ready := true.B
          when(io.cmd.bits.op === DmaOp.store_output) {
            state := sGather
          }.otherwise {
            state := sLoadActive
          }
        }
      }
    }

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
    }

    is(sIssue) {
      when(opReg === DmaOp.store_output) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := baseAddrReg + bytesWritten
        io.mem.req.bits.data := packBuf
        io.mem.req.bits.isWrite := true.B
        io.mem.req.bits.tag := 0.U
        io.mem.req.bits.mask := "hFF".U
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

    is(sWaitResp) {
      when(respFifo.io.deq.valid) {
        unpackIdx := 0.U
        state := sUnpack
      }
    }

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
    }

    is(sError) {
      io.error := true.B
      io.done := true.B
    }
  }
}
