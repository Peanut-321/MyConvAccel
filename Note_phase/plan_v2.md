# ConvAccel Execution Plan v2.1（实际执行记录）

基于 `docs/spec.md` v1.1。标记 [DONE] 的 Phase 已完成，内容按实际执行结果更新。标记 [TODO] 的是后续工作。

---

## Phase 0 — Spec 冻结 ✅ [DONE]

冻结 `docs/spec.md` v1.1。所有数值参数、指令编码、数据格式以 spec 为准。

**实际输出：** `docs/spec.md` v1.1。

---

## Phase 1 — 金模型 + 测试向量 ✅ [DONE]

纯 C 实现 `tools/matrix_conv_golden.c`，生成 4 组测试向量：

| 场景 | kernel | input | expected |
|------|--------|-------|----------|
| identity kernel | `identity_kernel_kernel.hex` | `identity_kernel_input.hex` | `identity_kernel_expected.hex` |
| box blur (all ones) | `box_blur_ones_kernel.hex` | `box_blur_ones_input.hex` | `box_blur_ones_expected.hex` |
| edge detect (Sobel-like) | `edge_detect_sobel_kernel.hex` | `edge_detect_sobel_input.hex` | `edge_detect_sobel_expected.hex` |
| saturation test | `saturation_test_kernel.hex` | `saturation_test_input.hex` | `saturation_test_expected.hex` |

**实际输出：** `tools/matrix_conv_golden.c` + `src/test/resources/*.hex`（共 12 个 hex 文件）。

---

## Phase 2 — ConvControl + RoCC 指令解码 ✅ [DONE]（路径偏离原计划）

**原计划：** `ConvAccel extends LazyRoCC`，直接依赖 Rocket Chip。

**实际执行：** 项目运行在纯 Chisel 环境，不依赖 Rocket Chip。因此拆分为两层：

| 文件 | 作用 | Chipyard 迁移时 |
|------|------|----------------|
| `ConvControl.scala` | funct7 指令解码 + 状态寄存器 + FSM | **保持不变，直接复用** |
| `ConvRoCCTestHarness.scala` | 手写临时 `RoCCCommand`/`RoCCResponse` 包 | **丢弃，替换为 LazyRoCCModuleImp** |

**指令集实现（spec §3）：**

| funct7 | 指令 | 行为 |
|--------|------|------|
| 0 | SET_ADDR_IN | rs1 → inAddrReg |
| 1 | SET_ADDR_KER | rs1 → kerAddrReg |
| 2 | SET_ADDR_OUT | rs1 → outAddrReg |
| 3 | START_ACCEL | 检查地址非空 + 对齐，拉 busy |
| 4 | POLL_STATUS | rd = {busy, done, overflow, addr_err} |

**测试：** 14 个 RoCC 指令测试通过（TC-01 ~ TC-13 + respPending）。

---

## Phase 3 — DMA 基础（load + store 串行）✅ [DONE]

**实际实现：** `ConvDMA.scala`（280 行），7-state FSM。支持 `load_input / load_kernel / store_output` 三条路径。

**子模块：** `SimpleMemIO.scala`（29 行）— 内存接口协议；`DmaCmd.scala`（18 行）— DMA 操作码枚举；`FakeScratchpadMemory.scala`（42 行）— 测试用假内存。

**对齐检查：** ADDR_IN/ADDR_OUT 8 字节对齐、ADDR_KER 2 字节对齐。违规 → addr_err。

**测试：** 10 个测试通过（load 2 + store 1 + align 4 + backpressure 3）。

---

## Phase 4 — DMA Load Path 流水线 ✅ [DONE]

**实际实现：** 响应 FIFO + inflight 窗口 + 并发 issue/unpack。store 路径不变。

**性能：** TC-LOAD-01 1537 → ~1025 cycles（33% 提升）。

**测试：** 14 个测试通过（10 已有 + 4 流水线边界）。

---

## Phase 4.5 — 模块接口冻结（跳过）⏭️ [SKIPPED]

**原计划：** 正式接口表文档 + 端到端骨架测试（DMA load → 旁路直连 → DMA store）。

**实际：** 未执行。Phase 5/6 开发时接口来自 spec 和已有代码，自然一致。骨架测试合并到 Phase 7 集成测试中。反压传导链在 Phase 7 debug 过程中逐步完善。

**评估：** 计划时的最大风险（接口不一致）在实际中未出现。因为 Phase 5/6 都从同一份 spec 和同一组测试向量出发，接口信号名和语义在开发前就已明确。但如果团队超过 2 人，这一步仍然建议保留。

---

## Phase 5 — LineBuffer（5 行滑动窗口）✅ [DONE]

**实际实现：** `LineBuffer.scala`（167 行）。5×32×16-bit 寄存器缓冲，每拍吞 1 像素、吐出 1 列 5 像素 + colValid。

**FSM：** sIdle → sPrime（灌 5 行初值）→ sActive（输出 32 行 × 36 列）→ sDone。

**Padding 处理：** top 2 行零、bottom 2 行零。列方向 colValid 控制零填充（outputCol 0-1 左 padding、34-35 右 padding）。

**复用加载：** sActive 期间逐像素填入 tmpRow，行尾移位时并入 buffer(4)，buffer(0) 被淘汰。

**测试：** 5 个测试通过。

---

## Phase 6 — 5×5 ShiftWindow + ConvUnit + ConvEngine ✅ [DONE]

**实际实现：** 4 个模块组成 6 拍流水线卷积引擎。

| 模块 | 文件 | 行数 | 说明 |
|------|------|------|------|
| ShiftWindow | `ShiftWindow.scala` | 44 | 5×5 寄存器窗口，colValid 控制右移/填零 |
| KernelROM | `KernelROM.scala` | 29 | 5×5 kernel 存储，DMA 写入、组合输出 |
| ConvUnit | `ConvUnit.scala` | 103 | 5 级流水 MAC 树：25→13→7→4→2→1 + 四舍五入 + 饱和 |
| ConvEngine | `ConvEngine.scala` | 51 | ShiftWindow + KernelROM + ConvUnit 组装 |

**流水线延迟：** 6 拍（RegNext 1 拍 + ShiftRegister 5 拍）→ `outValid`。

**测试：** ConvUnit 2 + KernelROM 2 + ShiftWindow 3 + ConvEngine 4 = 11 个 Phase 6 新增测试。

**金模型驱动验证：** identity kernel 的 ConvEngine 测试用金模型 expected.hex 逐元素比对，4 个场景通过。

**参考模板：** `src/main/scala/mac/MAC_REFERENCE.md` 记录 LazyRoCC 集成模式，供 Phase 8 参考。

---

## Phase 7 — 顶层集成 + Master FSM ✅ [DONE]

### 实际架构（与计划有偏离）

**计划 FSM（7 状态）：**
```
IDLE → LOAD_KERNEL → PRIME_3_ROWS → COMPUTE_LOOP(32行) → DRAIN_2_ROWS → DONE
```

**实际 FSM（5 状态）：**
```
sIdle → sLoadKernel → sLoadInput → sCompute → sDone
```

**关键差异：**

| 项目 | 计划 | 实际 | 原因 |
|------|------|------|------|
| FSM 状态数 | 7 | 5 | Priming 合并到 sLoadInput，Drain 合并到 sCompute 末尾 |
| inputQueue | 无 | Queue(UInt(16.W), 1024) | 预加载全部像素，释放 DMA 做 store |
| storeQueue depth | 64 | 2048 | Engine 在 sLoadInput 期间已产出 ~1000 个结果 |
| DMA store length | 2048 bytes | 2176 bytes | 1088 个 16-bit 结果（含 64 个 pipeline bubble） |
| goDone 条件 | 全部结果产生 | resultCnt >= 1088 && DMA done | 1088 = 32 行 × 34 元素/行 |

### 子模块连线

```
io.mem  ←→  ConvDMA.mem
ConvDMA.loadStream  →  sLoadKernel: engine.kernelData
                        sLoadInput: inputQueue.enq + LineBuffer.in (前 5 行)
ConvDMA.storeStream ←  storeQueue.deq

inputQueue.deq  →  LineBuffer.in (sLoadInput 后期 + sCompute 全程)
LineBuffer.colOut  →  ConvEngine.colIn
ConvEngine.result  →  storeQueue.enq

storeQueue 满  →  stall LineBuffer + ConvEngine（反压传导）
```

### Debug 过程中的 3 个关键 Bug

1. **storeQueue 反压缺失** — 结果在 sLoadInput 期间被丢弃。修复：stall 信号加入 `!storeQueue.io.enq.ready`，storeQueue depth 扩为 2048。

2. **LineBuffer tmpRow 行覆写** — padding 列期间 loadCol 回卷导致下一行数据污染 tmpRow。修复：`io.in.ready` 加入 `inImage` 门控。

3. **管线尾部结果丢失** — colValid 过早拉低导致 img_30/img_31 无 outValid 标记。修复：colValid 延长 2 拍至 outputCol 34-35。

### 测试结果

| 测试 | 名称 | cycles | 比对 | 状态 |
|------|------|--------|------|------|
| TC-TOP-01 | identity kernel e2e | 2428 | 0 / 1024 | ✅ |
| TC-TOP-02 | box blur e2e | 2428 | 0 / 1024 | ✅ |
| TC-TOP-03 | 背靠背 identity kernel | 2428 + 2428 = 4856 | 0 / 1024 × 2 | ✅ |

**性能：** 2428 cycles（< 2500 目标），3 个端到端测试 0 mismatch。

### 测试架构

使用 `ConvAccelTopTestHarness`（包装 ConvAccelTop + FakeScratchpadMemory）通过 prefill/readback 端口直接读写内存，替代 RoCC 指令控制。这是纯 ChiselSim 测试，不依赖 Rocket Chip。

---

## Phase 8 — Chipyard 集成 + Verilator 构建 ⏳ [TODO]

**需要新建的文件：**

| 文件 | 行数估计 | 说明 |
|------|----------|------|
| `HellaCacheAdapter.scala` | ~80 | SimpleMemIO → HellaCacheIO 桥接 |
| `ConvAccelRoCC.scala` | ~30 | LazyRoCC 包装器，参考 `mac/MAC_REFERENCE.md` |
| `WithConvAccel` Config | ~5 | Chipyard Config fragment |

**无需修改的文件：** ConvControl、ConvAccelTop、ConvDMA、LineBuffer、ConvEngine、ShiftWindow、ConvUnit、KernelROM — 全是纯 Chisel，直接复用。

**构建目标：** `make CONFIG=ConvAccelConfig` 成功，`+verbose` 日志显示 RoCC 指令发射。

**环境要求：** x86 Linux（macOS 上 Chipyard 工具链不兼容）。

---

## Phase 9 — Bare-Metal C 测试程序 ⏳ [TODO]

`conv_test.c`：通过 RoCC 宏驱动加速器 → CPU 金模型比对 → `rdcycle` 测速。

**测试场景：** 3 种 kernel（3×3 软件 pad 到 5×5、5×5 原生 identity、5×5 box blur）。

**目标：** `[VERIFY] PASS (1024/1024)` + `[SPEEDUP] ≥ 40×`。

---

## Phase 10 — 性能报告 + 答辩 ⏳ [TODO]

Cycle 分解、加速比图、资源报告（LUT/FF/BRAM/Fmax）、波形截图（DMA ∥ MAC 重叠区间）。答辩 Q&A 预备。

---

## 完成状态总览

```
Phase 0  ✅ Spec 冻结
Phase 1  ✅ 金模型 + 测试向量（4 组）
Phase 2  ✅ ConvControl + RoCC 指令解码（14 tests）
Phase 3  ✅ DMA 基础（10 tests）
Phase 4  ✅ DMA Load Path 流水线（14 tests）
Phase 4.5 ⏭️ 跳过（接口冻结 + 骨架测试）
Phase 5  ✅ LineBuffer（5 tests）
Phase 6  ✅ ShiftWindow + ConvUnit + ConvEngine（11 new tests, 36 total）
Phase 7  ✅ 顶层集成 + Master FSM（3 E2E tests, 47 total）
Phase 8  ⏳ Chipyard 集成
Phase 9  ⏳ Bare-Metal C 测试
Phase 10 ⏳ 性能报告 + 答辩
```

### 实际 vs 计划差异汇总

| 维度 | 计划 | 实际 |
|------|------|------|
| 开发环境 | 直接 Chipyard | 纯 Chisel 项目，后续迁移 |
| Phase 2 实现 | `LazyRoCC` | `ConvControl`（可复用）+ 临时 test harness |
| Phase 7 FSM | 7 状态 | 5 状态（priming/drain 合并） |
| inputQueue | 无，DMA 实时加载 | Queue(1024)，预加载策略 |
| storeQueue depth | 64 | 2048 |
| 总代码量 | ~800 行估算 | 3758 行（含测试） |
| 总测试数 | 25 估算 | 47 |
| E2E cycles | ~2450 | 2428 |
| E2E mismatch | 0 | 0 / 1024 |
