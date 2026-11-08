# CM4 · Memory Map 与 Context Retrieval 收口报告（Lead 终稿）

## 本次修改（2026-11-09，Lead 终稿，提交 Architect milestone review）

将 CM4 Worker 草案整理为 Lead 终稿：更新 CT21 为 APPROVED / DONE，引用 [CT21 B1 Architect 复核](B1_Architect复核.md)，确认 14 模块 **1885=1885**、`./scripts/check` 131/131、真实栈全景链、Map 交错、Resource 取消、Fake 固定集、Schema/81/common/VFS/Core 与零生产修改边界，并保留真实模型 `not-run` 与 M6 未实现项。CM4 DONE 由 Architect milestone review 最终裁定。

**日期**：2026-11-09
**状态**：**Lead 终稿；提交 Architect CM4 milestone review；CM4 未由 Lead 自行标 DONE**
**依据**：[CT21 B1 Architect 复核](B1_Architect复核.md)、[CT21 B1 Worker 交付](B1_Worker交付.md)、[CT21 B1 Lead 初验](B1_Lead初验.md)、[CT21 任务书](CT21_任务书.md)、[Architect 裁定](Architect_裁定.md)、[CT20 收口](../cm4-ct20/B2_Architect复核.md)
**基线**：CT17～CT21 APPROVED / DONE；14 模块 **1885=1885**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. CT17～CT21 状态与入口

| 任务 | 状态 | 入口/证据 |
| --- | --- | --- |
| CT17 VFS safeReplace + Resource | APPROVED / DONE | [CT17 收口](../cm4-ct17/CT17_收口报告.md) |
| CT18 Memory Map Snapshot/Generate/Publish | APPROVED / DONE | [CT18 收口](../cm4-ct18/CT18_收口报告.md) |
| CT19 Map 生命周期/Bootstrap/Wakeup | APPROVED / DONE | [CT19 B2 Architect 复核](../cm4-ct19/B2_Architect复核.md) |
| CT20 Search/Get/Retrieval | APPROVED / DONE | [CT20 B2 Architect 复核](../cm4-ct20/B2_Architect复核.md) |
| CT21 CM4 全景集成/评测/证据 | **APPROVED / DONE** | [CT21 B1 Architect 复核](B1_Architect复核.md) |

## 2. 测试与边界证据

- 14 模块源码 `@Test` = XML = **1885**，fail/error/skip = 0/0/0。
- CT21 B1 前基线 1882；新增 3 个真实栈 E2E，最终 1885。
- 全量 `./scripts/check`：Lead 独立 exit 0（131/131，2m14s）；Architect 复核 exit 0（131/131，2m18s）。
- Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：75 一致 + 6 个 CT17 已批准差异，缺失 0、未批准差异 0。
- CT21 B1 白名单：11 文件；9 个 stub/E2E/evaluation SHA 复算 **9/9** 一致；零生产修改。
- CT20 B1 19/19、CT20 B2 12/12、CT18 17/17 SHA 未变；common/build/dependency/Schema/SQL/VFS 零修改；Core 无 Ktor/serialization。
- `git diff --check` exit 0；CT21 白名单外变更 0。
- CT21 定向 `integration-tests:context:test`：Lead 11s / Architect 10s，28 tasks；三 E2E 各 1 例全绿。

## 3. 全景链证据

`Cm4SdkJourneyEndToEndTest` 使用真实 Factory、SQLite、LocalFs `/memory` 与 `/resources`、Ktor client 和 JDK stub：

```text
start
→ 5 Conversation Turns
→ Extraction
→ Raw/Pending
→ Governance
→ Organized/Source/watermark
→ Memory Map + safeReplace
→ bootstrap
→ Search Scanner + Rerank
→ getMemory/getRawMemory
→ Resource Meta/Read/Stream
→ close
```

实际请求序列：

```text
Extraction → Governance → Map → Search Scanner → Search Rerank
```

- Candidate Scanner 在空候选下 0 请求。
- Search 使用动态真实 `O-MEM-*` ID。
- Memory 正文含真实 Resource URI。
- Get/GetRaw 0 新增 LLM。
- close 后 0 新增请求，业务入口 CLOSED。

## 4. Map 发布/读取交错

`Cm4MapInterleavingEndToEndTest` 使用真实第二版 Organization 路径，而非预置退路：

- 第一轮 5 Turns 产生第一版完整 Map。
- 第二轮 5 Turns 经 Extraction/Candidate/Governance 产生第二条 Organized。
- 第二版 Map HTTP 响应通过确定性 gate 阻塞。
- 阻塞窗口重复 20 次 bootstrap，只能读取完整旧版；Front Matter/正文严格解析，旧 sourceUpdatedAt 不变。
- 释放 gate 后新版完整发布；`MemoryMapRenderer.parse` 严格成功。
- 有效 wakeup 再检查后 STALE/CURRENT 收敛 CURRENT。
- 单测试由 `withTimeout(60s)` 界定。

## 5. Search→Resource 取消与释放

`Cm4SearchResourceCancellationEndToEndTest`：

- Resource URI 来自 Search 返回的真实 Memory，不由测试直接拼接读取 URI。
- LocalFs Resource >128 KiB，正常 Stream 按 64 KiB 多块读取并与 Read/文件字节一致。
- collector 首块后取消：`trackedStreamCount` 1→0。
- 再次打开 stream 后 `closeAsync()`：stream 回收、close 有界返回、count=0。
- close 后新 collect 为 `ContextLifecycleException(CLOSED)`。

## 6. 固定集与版本

- 样本版本：`cm4-v1`。
- Map：10 条；Search：10 条；均为合成脱敏、自包含输入与语义约束。
- [README](evaluation/README.md)、[Map 样本](evaluation/memory-map.jsonl)、[Search 样本](evaluation/retrieval.jsonl)、[Fake 结果](evaluation/fake-results.md)、[版本/SHA](evaluation/versions.md)。
- Fake 只证明协议、预算、结构、ID 边界和确定性。
- JSONL 10+10 逐行可解析；必填字段 `id/version/task/input/semantic_constraints/expected_behavior/forbidden_errors` 全部在位。
- `versions.md` 12 个 Prompt/Schema SHA：Architect 与 Lead 均复算 **12/12** 一致。
- **real-model: not-run**；不宣称真实语义质量通过。

## 7. 记录性观察与验证边界

- **N1（不阻断）**：Journey close 后 0 新请求使用 100ms 有界静默窗口；Map gate 与 Resource release 已使用 barrier/轮询，close 核心断言仍是业务入口 CLOSED 与 stream 归零。
- **N2（不阻断）**：真实模型语义质量 `not-run`；Fake 只证明协议、预算、结构、确定性与生命周期。
- **D16 边界**：进程重启/WAL 恢复未在 CT21 执行，留 M6 最终验收。
- Map 交错使用单进程、单测试 <60s 路径；未做多日 Cron soak。
- Resource 流取消验证基于 LocalFs；WebDAV 取消硬实时性仍受 CT17 既有边界约束。

## 8. 能力与未实现项

已形成 CM4 SDK 逻辑闭环：

- Conversation ingestion → Extraction → Organization → Map。
- Bootstrap 缓存读取与 Map 刷新协调。
- Search/Rerank 与权威 Get/GetRaw。
- Resource Meta/Read/Stream 及取消/关闭释放。

未实现/未验收：

- HTTP 权限/路由、完整部署。
- FTS/Embedding/向量库。
- Dreaming、Backup。
- 进程重启/WAL 恢复（按 D16 留 M6）。
- 真实模型语义质量评测。
- CT22/CM5/M6。

## 9. 无 Git 与最终状态

- CT21 B1 APPROVED / DONE；CT21 整体 APPROVED / DONE。
- 真实模型 not-run。
- 无 `git add/commit/push/stash`、分支、回滚、发布或其他 Git 写操作。
- **本报告为 Lead 终稿，提交 Architect 做 CM4 milestone review；CM4 DONE 由 Architect 最终裁定。**
