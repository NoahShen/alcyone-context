# CT21 · Architect 裁定（D1～D16）

**日期**：2026-11-09
**状态**：**APPROVED：放行 CT21 B1（集成、取消清理、固定集评测与收口证据）**
**裁定对象**：[CT21 任务书](CT21_任务书.md)、[方案核对](方案核对.md)（D1～D16）
**基线**：CT17～CT20 APPROVED / DONE；14 模块 **1882=1882**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 0. 本次修改

Architect 独立核实方案核对 F1～F15 关键事实（5 个既有 E2E 覆盖缺口、ExtractionHttpStub 静态顺序限制、`trackedStreamCount`/`vfsForTesting`/`organizationMapWakeupForTesting` 接缝、`INVISIBLE_REFERENCE` 抑制先例、无 evals 目录）后逐项裁定 D1～D16。结论：**方案 APPROVED，放行 CT21 B1**；D5 附带真实 Organization 路径要求、D10 维持 10+10 并要求每条样本带语义约束；测试接缝仅限 internal @Suppress，不改变生产可见性。

## 1. 事实核实（Architect 独立）

- F1～F5/F6：5 个既有 E2E 各自覆盖子链（Extraction/Organization/Map/Retrieval/Resource），无单条全景链 ✓（目录扫描 + 文件核对）
- F7：`ExtractionHttpStub` 为顺序静态响应（`responses[minOf(index, lastIndex)]`），无法按 prompt 路由或读动态 ID ✓
- F8：Organization 生成运行期动态 `O-MEM-*` ID；CT20 Scanner/Rerank 校验批内/子集 ID ✓
- F9：`AlcyoneVfs.trackedStreamCount` internal 存在；`AlcyoneContextResourceIntegrationTest` 已有取消/关闭释放先例 ✓
- 测试接缝先例：`MemoryMapGenerationEndToEndTest` 已使用 `@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")`；Impl 有 `vfsForTesting()`/`organizationMapWakeupForTesting()` internal ✓
- F10：无 `evals/` 或固定 CM4 评测集 ✓
- F12/F13：14 模块 1882=1882、131 tasks、生产 API 已具备全景链所需能力（CT20 收口）✓

## 2. 逐项裁定

| # | 裁定 | 说明 |
| --- | --- | --- |
| D1 | **单 B1、零生产修改** | 只新增 integration tests + docs/evaluation + 收口文档；发现生产缺陷停止并单独报告，CT21 不顺手修生产 |
| D2 | **全景链单测试 `Cm4SdkJourneyEndToEndTest`** | 覆盖 Conversation→Extraction→Organization→Map→Bootstrap→Search/Get→Resource→close 全闭环；允许测试内部分段断言 |
| D3 | **动态 HTTP stub 按 System Prompt 路由 + 真实 SQLite 只读动态 ID** | 测试专用，不进生产源码/公共框架；不记录/泄露 Key |
| D4 | **Map 交错独立测试 `Cm4MapInterleavingEndToEndTest`** | 与全景链分离，避免链过长 |
| D5 | **第二版触发：优先真实 Organization 路径**——若测试稳定性不可行，允许预置第二版 Organized + internal wakeup 接缝（`@Suppress` + `organizationMapWakeupForTesting()`），不得改变生产可见性 | 附带条件：方案推荐的"直接预置"是退路而非首选 |
| D6 | **交错断言：阻塞中只见完整旧版、释放后完整新版、随后 stale→CURRENT** | Front Matter 严格解析；无半写/空正文/破损 |
| D7 | **Search→Resource 取消独立测试 `Cm4SearchResourceCancellationEndToEndTest`** | Resource URI 必须来自 Search/Get 返回的真实 Memory 内容 |
| D8 | **Stream 释放断言走 `vfsForTesting().trackedStreamCount`** | barrier/轮询，不用固定 sleep 猜测；与既有 Resource 测试口径一致 |
| D9 | **固定集放 `docs/context/tasks/cm4-ct21/evaluation/`** | 不建通用评测平台 |
| D10 | **维持首版 Map 10 + Search 10 条高价值合成样本** | 不扩到 20～50；每条必须附语义约束/预期行为说明，宁缺毋滥 |
| D11 | **JSONL 输入 + Markdown Fake 结果** | 不引入解析框架 |
| D12 | **`cm4-v1` 版本 + 每 Prompt/Schema SHA + Fake Profile/Task + 请求顺序** | 可复现、Fake/真实分离 |
| D13 | **统一 `real-model: not-run`** | Fake 只标协议/预算/结构通过，不填语义质量通过 |
| D14 | **`CM4_收口报告.md` 草案（Lead）+ Architect 最终 milestone review** | 保持 Lead→Architect 分工 |
| D15 | **证据矩阵：14 模块、131 tasks（变化需解释）、81 基线 75+6、Schema、common/VFS/Core 依赖、git diff check** | 与 CM3/CT20 收口口径一致 |
| D16 | **不做进程重启/WAL 测试，留 M6** | CT21 聚焦 SDK 闭环与固定集 |

## 3. 附加裁定（必要澄清）

- **B1 白名单方向**：3 个集成测试 + 1 个测试专用 stub + `evaluation/` 5 文件 + `B1_Worker交付.md` + `CM4_收口报告.md`（草案）；由 Lead 冻结精确清单。**不得**修改任何生产源码、既有测试、Schema/SQL/依赖/构建。
- **动态 stub 只读 SQLite**：禁止 stub 写库或注入状态；ID 唯一来源是真实 Organization 提交结果。
- **Map 交错测试时长**：单测试有界（建议 <60s）；gate 释放使用确定性 barrier，不用 sleep。
- **全景链 LLM 请求数**：测试必须断言请求类型序列（Extraction→Governance→Map→Scanner→Rerank）且 close 后无新增请求。
- **evaluation 样本合成与脱敏**：不得使用真实用户数据；样本正文自包含。
- **若集成暴露生产缺陷**：停止 → Lead 报告 → Architect 裁定 R1 或新批次；CT21 不修生产。

## 4. 结论

- **CT21 方案 APPROVED**；D1～D16 全部裁定（D5 附加真实路径优先、D10 维持 10+10 且要求语义约束）。
- **放行 CT21 B1**：Lead 冻结 B1 委派单与白名单后派发 Worker。
- CT21 B1 复核通过后由 Lead 提交 CM4 收口报告终稿，Architect 出 CM4 milestone review，裁定 CM4 DONE。
- CT22/CM5 未启动授权；真实模型 not-run；无 Git 写操作；基线 1882=1882。
