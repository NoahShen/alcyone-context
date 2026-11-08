# CT21 B1 · Architect 复核

**日期**：2026-11-09
**状态**：**CT21 B1 APPROVED / DONE；CT21 整体 APPROVED / DONE**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、[CM4 收口报告草案](CM4_收口报告.md)、Worker `059d6c59-ce9e-4e68-9055-b45b238506a1`
**依据**：[Architect 裁定](Architect_裁定.md) D1～D16、[B1 委派单](B1_委派单.md)

## 0. 本次修改

Architect 独立复核 CT21 B1：9/9 文件 SHA 一致、三条真实栈 E2E 与动态 stub 逐项核对、12/12 Prompt/Schema SHA 复算、10+10 JSONL 样本与字段校验、定向与全量实跑通过、零生产修改边界确认。结论 **B1 APPROVED / DONE；CT21 整体 APPROVED / DONE**（单 B1 批次即完整任务）。N1/N2 均为记录性观察。

## 1. 独立证据

| 项 | 结果 |
| --- | --- |
| 9 文件 SHA 复算 | stub 1 + E2E 3 + evaluation 5，逐项一致（9/9 OK） |
| JSONL 样本 | Map 10 条 + Search 10 条逐行可解析；必填字段（id/version/task/input/semantic_constraints/expected_behavior/forbidden_errors）全部在位 |
| Prompt/Schema SHA | `versions.md` 12/12 复算一致 |
| 定向测试 | `:integration-tests:context:test --rerun-tasks` exit 0（10s，28 tasks）；CT21 三 E2E 各 1 例全绿 |
| 全量 `./scripts/check` | exit 0（2m18s，131/131 tasks executed） |
| 14 模块计数 | source `@Test` = XML = **1885=1885**；B1 前 1882 + 3 = 1885 |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| 零生产修改 | `AlcyoneContextImpl`/`AlcyoneContext`/`MemorySearchService`/`MemoryMapPipeline` SHA 与 CT20 B2 终态一致（4/4）；既有测试（ExtractionHttpStub 等）未变；common diff 空 |
| real-model 标注 | evaluation 5 文件 + 收口报告均标注 `real-model: not-run` |

## 2. 实现逐项核对（D1～D16 落实审查）

| 裁定 | 复核结果 |
| --- | --- |
| D3 动态 stub | `DriverManager.getConnection("jdbc:sqlite:…?mode=ro")` 只读查询动态 ID；只记录 `authorizationPresent` 布尔，不保存 Key 原文；无写库/注入。通过 |
| D2 全景链 | 5 Turns → Extraction → Raw/Pending → Governance → Organized/Source → Map → Bootstrap → Search → Get/GetRaw → Resource Meta/Read/Stream → close；请求序列断言严格 `EXTRACTION→GOVERNANCE→MEMORY_MAP→SCANNER→RERANK`；Get/GetRaw 3 次重复 0 新请求；close 后 0 新请求、CLOSED。通过 |
| D5/D4/D6 Map 交错 | 第二版走**真实 Organization 路径**（第二轮 5 Turns ingest，非预置退路）；`CountDownLatch` gate 无 sleep；阻塞窗口 20 次 bootstrap 均 `MemoryMapRenderer.parse` 严格成功且 sourceUpdatedAt 不变；释放后新版完整、wakeup 后收敛 CURRENT。通过 |
| D7/D8 Search→Resource 取消 | Resource URI 取自 Search/Get 返回 Memory 正文（正则提取自真实内容）；collector 取消 `awaitUntil { trackedStreamCount == 0 }`；closeAsync 带打开 stream 有界返回（10s timeout）且 count 1→0；close 后新 collect CLOSED；`awaitUntil` 轮询 20ms 无固定 sleep。通过 |
| D9/D10/D11/D12/D13 评测 | 目录/10+10/JSONL 自包含字段/cm4-v1 版本与 12 SHA/Fake 只标协议结构/统一 not-run。通过 |
| D1/D15 边界 | 白名单外变更 0；81 基线 75+6；Core 无 Ktor/serialization；构建/依赖零修改。通过 |
| D16 | 未做进程重启/WAL，留 M6。通过 |

## 3. N1/N2 裁定

**均为记录性观察，不要求 R1。**

- N1（close 后 0 新请求用 100ms 有界静默窗口）：Map gate 与 Resource release 已用确定性 barrier/轮询；close 后的静默检查是补充性证据而非核心断言（核心是业务入口 CLOSED + stream 归零）。若后续出现异步收尾波动再改为 barrier。
- N2（真实模型语义质量 not-run）：CM4 边界已明确；真实模型评测需单独授权。

## 4. 结论

**CT21 B1 APPROVED / DONE；CT21 整体 APPROVED / DONE。**

- 11 文件白名单齐备；9 代码/评测文件 SHA 一致；零生产修改。
- A01～A08 全部落实：全景链、权威数据、Map 交错、Search、Get、Resource、取消/关闭、评测/收口。
- 14 模块 **1885=1885**；`./scripts/check` exit 0；Schema/CT17～CT20/common/VFS 边界全部通过。
- **下一步（CM4 收口流程，按 D14）**：
  1. Lead 基于 Worker 草案整理 `CM4_收口报告.md` 终稿（可引用 B1 复核证据，更新状态为 CT21 DONE）。
  2. 提交 Architect 做 **CM4 milestone review**，裁定 CM4 DONE。
- CT22/CM5 未启动授权；真实模型 not-run；无 Git 写操作。
