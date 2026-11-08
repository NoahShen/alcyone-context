# CT21 B1 · Lead 初验

## 本次修改（2026-11-09，B1 Lead 初验）

完成 CT21 B1 独立初验：核对 11 文件白名单与 SHA，审查动态 HTTP stub、SDK 全景链、Map 发布/读取交错、Search→Resource 取消/释放，以及 Map/Search 10+10 固定集与 CM4 收口草案；独立复跑 integration 测试、Spotless、三组定向 E2E 和全量 `./scripts/check`。结论为 **B1 初验通过、提交 Architect 复核；CM4 未 DONE，CT22/CM5 未启动**。

**日期**：2026-11-09
**状态**：**B1 IN_REVIEW；提交 Architect 独立复核**
**Worker**：`059d6c59-ce9e-4e68-9055-b45b238506a1`（`codex-magpie/group/worker`、`auto-review`、high）
**依据**：[B1 委派单](B1_委派单.md)、[B1 Worker 交付](B1_Worker交付.md)、[Architect 裁定](Architect_裁定.md)、[CT21 任务书](CT21_任务书.md)、[CM4 收口草案](CM4_收口报告.md)
**基线**：CT17～CT20 APPROVED / DONE；14 模块 **1882=1882**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`

## 1. 初验结论

**CT21 B1 Lead 初验通过，提交 Architect 独立复核。**

- Worker 严格新增 B1 委派单 §3 的 11 个文件：4 个 integration test/helper + 5 个 evaluation 文件 + 2 份报告；白名单外 B1 变更 0。
- 新增 3 个真实栈 E2E，14 模块源码 `@Test` 与 XML 均为 **1885=1885**，fail/error/skip = **0/0/0**。
- 全景链实际请求序列为 `Extraction → Governance → Map → Scanner → Rerank`，Get/GetRaw 0 新增请求，close 后 0 新增请求。
- Map 交错走真实第二版 Organization 路径；阻塞中只读完整旧版，释放后完整新版并收敛 CURRENT。
- Search 返回真实 Resource URI；collector cancel 与 Context close 后 `trackedStreamCount` 均归零。
- evaluation 为 Map 10 + Search 10 条合成脱敏 JSONL；12 个 Prompt/Schema SHA 与版本记录一致；真实模型 `not-run`。
- 零生产修改；CT20 B1/B2、CT18、VFS/common/Schema/依赖/构建边界通过。

## 2. 白名单与 SHA

### 2.1 文件集

- 委派单 §3 的 11 个文件全部存在；9 个 integration/evaluation 文件 SHA 与 [B1 Worker 交付](B1_Worker交付.md) §2 逐项一致。
- 两份报告不内嵌自引用 SHA。
- 实际组成为：1 个动态 stub + 3 个 integration E2E + 5 个 evaluation 文件 + 2 份报告。

### 2.2 关键文件 SHA

| 文件 | Lead 复算 SHA-256 |
| --- | --- |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/Cm4HttpStub.kt` | `5dfadde5059e4eb911f15782193a6686bb0749297a75ba8547394806a8a12524` |
| `.../Cm4SdkJourneyEndToEndTest.kt` | `626e3b5938c32ecf4c0213c19aa05ae6ccc71c1a177bbf083d90b544b1fbafb0` |
| `.../Cm4MapInterleavingEndToEndTest.kt` | `bf5861b8b0eff5c39da446e3c899fe6c70912172d4e61dce42de7f8435f18813` |
| `.../Cm4SearchResourceCancellationEndToEndTest.kt` | `0ce1b5d2406c990b51b2fa97df1c1dc22d626705eb254a78eb173c619aaca6ef` |
| `docs/context/tasks/cm4-ct21/evaluation/memory-map.jsonl` | `e1624550c971b14fa7222e0535690f0bef71d7b5b04127602fee16cdef99d0ac` |
| `docs/context/tasks/cm4-ct21/evaluation/retrieval.jsonl` | `6e900f0e5790d4f13829cc6511564eec5a737a0e289f9a1c9a4b1b566fb37b1a` |

## 3. 独立实跑证据

| 命令 | exit | 耗时 | 关键结果 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 11s | 28 tasks；完整 integration-context 全绿 |
| `./scripts/dev gradle :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 2s | 5 tasks；格式通过 |
| `./scripts/check` | 0 | 2m14s | 131/131 tasks；全模块构建、Spotless、测试通过 |
| CT21 三 E2E 定向 | 0 | 9s | Journey / Map Interleaving / Search Resource 全部通过 |
| 完整 integration-context 恢复运行 | 0 | 9s | 27 例通过；恢复完整 XML |
| `git diff --check` | 0 | - | 空输出 |

### 3.1 14 模块 source/XML

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 23 | 23 | 0 | 0 | 0 |
| context-api | 38 | 38 | 0 | 0 | 0 |
| context-core | 119 | 119 | 0 | 0 | 0 |
| context-persistence | 178 | 178 | 0 | 0 | 0 |
| context-runtime | 623 | 623 | 0 | 0 | 0 |
| context-server | 30 | 30 | 0 | 0 | 0 |
| integration-context | 27 | 27 | 0 | 0 | 0 |
| vfs-api | 67 | 67 | 0 | 0 | 0 |
| vfs-core | 316 | 316 | 0 | 0 | 0 |
| vfs-persistence | 52 | 52 | 0 | 0 | 0 |
| vfs-runtime | 64 | 64 | 0 | 0 | 0 |
| vfs-storage | 166 | 166 | 0 | 0 | 0 |
| vfs-git-backup | 73 | 73 | 0 | 0 | 0 |
| integration-vfs | 109 | 109 | 0 | 0 | 0 |
| **合计** | **1885** | **1885** | **0** | **0** | **0** |

B1 前 **1882**，新增 **3**，最终 **1885=1885**。

## 4. 证据核对

### 4.1 动态 HTTP stub

- 按 System Prompt 标题路由 Extraction / Candidate / Governance / Map / Search Scanner / Rerank。
- 动态 Organized ID 只通过 SQLite `mode=ro` 读取。
- 静态扫描未见 INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/executeUpdate。
- 只记录 Authorization 是否存在，不保存 Key 原文。
- 第二版 Map 使用 `CountDownLatch` gate，不使用固定 sleep。

### 4.2 SDK 全景链

- 真实 Factory + SQLite + LocalFs `/memory`/`/resources` + Ktor client + JDK stub。
- 5 Turns → Extraction → Raw/Pending → Governance → Organized/Source/watermark → Map → Bootstrap → Search → Get/GetRaw → Resource → close。
- 实际请求序列断言严格为 `Extraction → Governance → Map → Scanner → Rerank`。
- 空 Candidate Scanner 为 0 请求；Search 使用动态真实 ID。
- Memory 内容含真实 Resource URI；Get/GetRaw 0 新增 LLM。

### 4.3 Map 交错

- 第二版触发走真实 Organization 路径，而非预置退路。
- 单测试 `withTimeout(60_000L)` 有界。
- 阻塞窗口 20 次 bootstrap 只返回完整旧版，Front Matter 严格解析，旧 sourceUpdatedAt 不变。
- 释放后新版完整，sourceUpdatedAt 追平 SQLite，随后 CURRENT。

### 4.4 Search→Resource 取消

- Resource URI 来自 Search 返回的真实 Memory。
- 文件 >140 KiB，正常读取覆盖多个 64 KiB chunk，字节一致。
- 首块后取消：trackedStreamCount 1→0。
- 再次打开后 Context closeAsync：stream 1→0，close 有界返回。
- close 后新 collect CLOSED。

## 5. 固定集核对

- `memory-map.jsonl`：10 条，全部可逐行解析，task=memory-map，version=cm4-v1。
- `retrieval.jsonl`：10 条，全部可逐行解析，task=retrieval，version=cm4-v1。
- 每行均含 input / semantic_constraints / expected_behavior / forbidden_errors / notes。
- `versions.md` 记录 12 个 CM4 Prompt/Schema SHA；Lead 复算 **12/12 一致**。
- Fake 结果明确只证明协议/预算/结构/确定性。
- README、Fake results、versions 与 CM4 草案均包含 `real-model: not-run`。

## 6. 边界核对

### 6.1 CT20 / CT18 / Schema / VFS

- CT20 B1 Worker 交付 19 个代码/资源/测试 SHA：**19/19 一致**。
- CT20 B2 Worker 交付 12 个代码/测试 SHA：**12/12 一致**。
- CT18 17 个代码/测试 SHA：**17/17 一致**。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。

### 6.2 零生产修改与工程边界

- CT21 B1 只新增 integration-tests/context 与 docs/evaluation/report 文件。
- CT20 B1/B2 与 CT18 SHA 不变；CT17～CT20 组件语义未改。
- 构建文件、Gradle 版本目录、common、Schema/SQL、依赖无 B1 变更。
- Core 主源码无 Ktor/serialization；测试 suppression 仅限 integration test 文件。
- 无 Git 写操作。

## 7. 非阻断观察

**N1：全景链 close 后 0 新请求使用 100ms 有界静默窗口。** Map gate 与 Resource release 已按要求使用 barrier/轮询；close 后仅做固定有界静默检查。若后续出现异步收尾波动，可改为基于 worker/stream/request barrier，不影响当前行为证据。

**N2：全量评测仍需真实模型授权。** 当前 10+10 样本和 Fake 结果只证明协议/结构/确定性，不能作为 Map/Search 语义质量结论。

## 8. 未运行项与残余风险

- 真实模型：**not-run**；Fake 不证明语义相关性或摘要质量。
- 进程重启/WAL：按 D16 留 M6。
- Map 交错为单进程、单测试有界运行；未做多日 Cron soak。
- LocalFs Resource 取消已证明；WebDAV 硬实时取消仍受 CT17 既有边界约束。
- HTTP Server/ACL/部署、Dreaming、Backup、CT22/CM5 未启动。

## 9. 当前状态

- **CT21 B1：IN_REVIEW，Lead 初验通过，提交 Architect 独立复核。**
- **CM4：IN_PROGRESS；最终收口待 Architect milestone review。**
- **CT22/CM5：未启动授权。**
- 真实模型 not-run；无 Git add/commit/push/stash、分支、回滚或发布操作。
