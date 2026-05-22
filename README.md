# MyConvAccel

RISC-V RoCC convolution accelerator written in Chisel — a hardware generator for 2D convolution with a pipelined DMA-load, line-buffered, sliding-window architecture.

## Overview

MyConvAccel is a standalone Chisel hardware design implementing a 5×5 2D convolution accelerator targeting the RISC-V RoCC (Rocket Custom Coprocessor) interface. It accepts 32×32 input feature maps with same-padding this produces a 32×32 output and performs Q8.8 fixed-point convolution against a 5×5 kernel.

The design is currently **standalone** (no Chipyard/Rocket Chip dependency) with plans for Chipyard integration on an x86 Linux machine.

## Architecture

```
ConvDMA ──→ LineBuffer ──→ ConvEngine (ShiftWindow + KernelROM → ConvUnit)
  │                            │
  │  load / store via          │  5-stage pipelined MAC tree
  │  SimpleMemIO               │  5 vertical pixels / cycle
  │                            │
  └── FakeScratchpadMemory ────┘
```

- **ConvDMA** — Pipelined DMA engine with concurrent issue/unpack, `respFifo` decoupling, and `inflightCount` flow control (max 4 in-flight)
- **LineBuffer** — 5×32 row buffer converting row-major DMA pixels to 5-wide column-major output
- **ConvEngine** — Sliding-window convolution pipeline (`ShiftWindow` → `ConvUnit`), 5-stage MAC tree with stall support
- **ConvControl** — RoCC instruction decode (command, response, status registers)
- **ConvAccelTop** — Master FSM orchestrating the full compute flow: idle → load kernel → prime buffer → compute → done

### Key Specs

| Parameter | Value |
|-----------|-------|
| Input size | 32 × 32 |
| Kernel size | 5 × 5 |
| Padding | Same (output = 32 × 32) |
| Data type | Q8.8 fixed-point |
| MAC pipeline | 5 stages |
| Line buffer | 5 rows × 32 columns |

## Project Structure

```
src/main/scala/matrix/
  ConvAccelTop.scala    — Master FSM / top-level integration
  ConvDMA.scala         — DMA load/store engine
  LineBuffer.scala      — Row-to-column buffer
  ConvEngine.scala      — Sliding-window conv pipeline
  ShiftWindow.scala     — 5×5 window register
  ConvUnit.scala        — 5-stage MAC tree
  KernelROM.scala       — Kernel weight storage
  ConvControl.scala     — RoCC instruction interface
  SimpleMemIO.scala     — Memory port definitions
  DmaCmd.scala          — DMA command encoding
  FakeScratchpadMemory.scala — Testbench scratchpad model

src/test/scala/matrix/  — Unit & integration tests (55+ tests)
docs/spec.md            — Design specification
Note_phase/             — Phase design documents
```

## Build & Test

**Prerequisites:** JDK 11+, SBT

```sh
# Run all tests
sbt test

# Run a specific test suite
sbt 'testOnly matrix.ConvAccelTopSpec'
```

## Implementation Status

| Phase | Component | Status |
|-------|-----------|--------|
| 2 | ConvControl (RoCC decode) | Done |
| 3 | ConvDMA (serial) | Done |
| 4 | ConvDMA (pipelined) | Done |
| 5 | LineBuffer | Done |
| 6 | ConvEngine / ShiftWindow / ConvUnit | Done |
| 7 | ConvAccelTop master FSM | Done |
| 8+ | Chipyard integration | Planned (requires x86) |

## Toolchain

- **Chisel** 7.7.0 (Scala 2.13.18)
- **ScalaTest** 3.2.19
- **SBT** as build tool
