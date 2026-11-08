# CT21 B1 · CM4 全景集成、取消清理、固定集评测与收口证据 Worker 交付

## 本次修改（2026-11-09，B1 初始交付）

新增本交付报告，记录 CT21 B1 的 11 文件白名单、动态 HTTP stub、三条真实栈 E2E、Map 交错、Search→Resource stream 取消、10+10 固定集、版本/SHA、三条验收命令、14 模块 **1885=1885**、CT20/CT19/CT18/Schema/81 边界和残余风险。本文件不把 B1、CM4 或 CT21 标记为 DONE。

**日期**：2026-11-09
**状态**：**提交 Lead 初验，CM4 未 DONE，CT22/CM5 未启动**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT21 任务书](CT21_任务书.md)、[方案核对](方案核对.md)、[CT20 收口](../cm4-ct20/B2_Architect复核.md)
**基线**：CT17～CT20 APPROVED / DONE；14 模块 **1882=1882**；Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`；工作区既有 CM3/CT17～CT20 未提交交付保持不动。

## 1. 交付结论

严格新增 B1 委派单 §3 的 11 个文件：**4 个 integration test/helper + 5 个 evaluation 文件 + 2 份报告**。

- 新增 3 个真实栈 E2E：SDK 全景链、Map 发布/读取交错、Search→Resource 取消/关闭。
- 新增测试专用动态 HTTP stub：按 System Prompt 路由，动态 ID 只从真实 SQLite 只读查询。
- 新增 Map 10 + Search 10 条合成样本、Fake 结果与版本 SHA 记录。
- 14 模块源码 `@Test` 与 XML 均为 **1885**，fail/error/skip = **0/0/0**。
- `integration-tests/context:test`、integration Spotless、全量 `./scripts/check` 均通过。
- 零生产修改；真实模型 **not-run**；无任何 Git 写操作。

## 2. 文件清单与 SHA-256

### 2.1 Integration test/stub（4）

| 文件 | SHA-256 |
| --- | --- |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/Cm4HttpStub.kt` | `5dfadde5059e4eb911f15782193a6686bb0749297a75ba8547394806a8a12524` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/Cm4SdkJourneyEndToEndTest.kt` | `626e3b5938c32ecf4c0213c19aa05ae6ccc71c1a177bbf083d90b544b1fbafb0` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/Cm4MapInterleavingEndToEndTest.kt` | `bf5861b8b0eff5c39da446e3c899fe6c70912172d4e61dce42de7f8435f18813` |
| `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/Cm4SearchResourceCancellationEndToEndTest.kt` | `0ce1b5d2406c990b51b2fa97df1c1dc22d626705eb254a78eb173c619aaca6ef` |

### 2.2 Evaluation（5）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct21/evaluation/README.md` | `6a84118e02386fef965f2314e2832f909a64e084cf8d007eda5617916b440f4f` |
| `docs/context/tasks/cm4-ct21/evaluation/memory-map.jsonl` | `e1624550c971b14fa7222e0535690f0bef71d7b5b04127602fee16cdef99d0ac` |
| `docs/context/tasks/cm4-ct21/evaluation/retrieval.jsonl` | `6e900f0e5790d4f13829cc6511564eec5a737a0e289f9a1c9a4b1b566fb37b1a` |
| `docs/context/tasks/cm4-ct21/evaluation/fake-results.md` | `6827714f62d7a9c2f70bc62d6d8f6d10e00f6cab2aafe181435a7e8a82476eb7` |
| `docs/context/tasks/cm4-ct21/evaluation/versions.md` | `0c53c08cd89836bdee646d815ec5063374f8249b2c1f7e0412c6b7e2c6672c52` |

### 2.3 报告（2）

| 文件 | SHA-256 |
| --- | --- |
| `docs/context/tasks/cm4-ct21/B1_Worker交付.md` | 本文件自身不内嵌自引用 SHA |
| `docs/context/tasks/cm4-ct21/CM4_收口报告.md` | 报告自引用不内嵌自身 SHA |

白名单外 B1 代码/测试/生产/构建变更：**0**。

## 3. 实现说明

### 3.1 Cm4HttpStub

- JDK `HttpServer`，测试专用，不进入生产源码。
- 按 System Prompt 标题路由：
  - Extraction
  - Organization Candidate Scanner
  - Organization Governance
  - Memory Map
  - Memory Search Scanner
  - Memory Search Rerank
- Extraction `source_turn_ids` 与 Search 动态 Organized ID 均通过 SQLite `mode=ro` 查询。
- stub 不写数据库、不注入业务状态、不伪造组织/记忆 ID。
- 记录请求类型/顺序/body 与 Authorization **是否存在**；不保存/输出 API Key 原文。
- 第二版 Map 使用 `CountDownLatch` 确定性 gate，不使用固定 sleep。

### 3.2 全景链

- 公共 Factory + initializeDatabase + 真实 SQLite。
- 真实 VFS LocalFs `/memory` 与 `/resources` 双挂载。
- 真实 Ktor client + 本机 stub。
- 5 Conversation Turns → Extraction → Raw/Pending → Governance → Organized/Source/watermark → Map safeReplace → Bootstrap → Search → Get/GetRaw → Resource Meta/Read/Stream → close。
- 请求序列固定断言：**Extraction → Governance → Map → Scanner → Rerank**；空候选 Candidate Scanner 0 请求。
- Search 只使用动态真实 `O-MEM-*` ID；Memory 内容含真实 Resource URI。
- Get/GetRaw 后请求数不增；close 后 0 新增请求，业务入口 CLOSED。

### 3.3 Map 交错

- 第一轮 5 Turns 产生第一条 Organized 与第一版 Map。
- 第二轮 5 Turns 走真实 Extraction → Candidate Scanner（空候选）→ Governance，产生第二条 Organized。
- 第二版 Map HTTP 响应被确定性 gate 阻塞。
- 阻塞窗口重复 20 次 bootstrap：每次只能是完整旧版 Markdown，`MemoryMapRenderer.parse` 严格成功，旧 sourceUpdatedAt 不变。
- 释放 gate 后第二版完整发布，新 Front Matter / Overview 可严格解析。
- 再调用 production effective Map wakeup，状态由 STALE/CURRENT 收敛 CURRENT。

### 3.4 Search→Resource 取消

- Search 返回的 Memory 正文提供真实 Resource URI，不由测试直接拼接读取 URI。
- Resource >128 KiB，覆盖多个 64 KiB chunk。
- Meta/Read/Stream 的 URI、大小、字节一致。
- collector 首块后挂起并取消；轮询 `trackedStreamCount` 从 1 收敛 0。
- 再次打开 stream 后用 `closeAsync()` 关闭 Context；stream 回收、close 有界返回。
- close 后新 collect 抛 `ContextLifecycleException(CLOSED)`。

## 4. 请求序列与清理证据

| 场景 | 实际序列/结果 |
| --- | --- |
| 全景链 | Extraction → Governance → Map → Scanner → Rerank |
| Organization Candidate | 空候选 0 请求 |
| Map 空库启动检查 | 0 LLM |
| Map 交错第一轮 | Extraction → Governance → Map |
| Map 交错第二轮 | Extraction → Candidate Scanner → Governance → blocked Map |
| Search→Resource | Scanner → Rerank |
| 全景链 close 后 | 0 新增请求；Search/Get/GetRaw/Resource CLOSED |
| Resource collector cancel | trackedStreamCount 1 → 0 |
| Context closeAsync with stream | trackedStreamCount 1 → 0；close 有界返回 |
| close 后新 collect | CLOSED |

## 5. 固定集评测

- `memory-map.jsonl`：10 条。
- `retrieval.jsonl`：10 条。
- 两份 JSONL 均由 `jq -s length` 验证为 10 且逐行 JSON 可解析。
- 样本字段含 id/version/task/input/semantic_constraints/expected_behavior/forbidden_errors/notes。
- 样本版本：`cm4-v1`。
- Prompt/Schema SHA、Fake Profile/Task、请求顺序记录于 `versions.md`。
- Fake 结果只标协议/预算/结构/确定性；所有评测文档明确 `real-model: not-run`。

## 6. 验收命令与结果

### 6.1 运行环境说明

受限沙箱 Gradle 仍可能触发 FileLock socket 限制；最终命令按项目入口在获批环境实跑。代码写完后执行了 `./scripts/dev gradle spotlessApply`。

| 命令 | exit | 耗时 | 关键输出 |
| --- | ---: | ---: | --- |
| `./scripts/dev gradle spotlessApply` | 0 | 2s | `BUILD SUCCESSFUL`；57 tasks，2 executed / 55 up-to-date |
| `./scripts/dev gradle :integration-tests:context:test --rerun-tasks --console=plain` | 0 | 10.01s | `BUILD SUCCESSFUL in 9s`；28 tasks executed |
| `./scripts/dev gradle :integration-tests:context:spotlessCheck --rerun-tasks --console=plain` | 0 | 1.62s | `BUILD SUCCESSFUL in 1s`；5 tasks executed |
| `./scripts/check` | 0 | 134.85s | `BUILD SUCCESSFUL in 2m 14s`；131/131 tasks executed |
| `git diff --check` | 0 | - | 空输出 |

## 7. 14 模块源码 `@Test` 与 XML

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

## 8. CT17～CT20 / Schema / 81 / 边界

- CT20 B1 19 个代码/资源/测试 SHA：**19/19 一致**。
- CT20 B2 12 个代码/测试 SHA：**12/12 一致**。
- CT18 17 个代码/测试 SHA：**17/17 一致**。
- CT19 保持 CT20 B2 终态；CT21 未修改任何 CT17～CT20 生产/测试文件。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- 81 VFS 基线：**75 一致 + 6 个 CT17 已批准差异，缺失 0，未批准差异 0**。
- common/build/dependency/VFS/SQLite Schema/SQL 零修改；Core 无 Ktor/serialization。
- 白名单外变更 0。

## 9. 未运行项与残余风险

- 真实模型：**not-run**；所有 LLM 请求均由本机 JDK stub 响应，不证明真实语义质量。
- 未做进程重启/WAL 恢复测试，按 D16 留 M6。
- Map 交错测试使用真实 Organization 第二版路径，但仍在单进程、单测试 <60s 范围内。
- 动态 stub 从 SQLite 只读查询 ID；若未来 Schema/表名变化需同步测试 helper，当前 Schema 固定。
- Resource stream 使用 LocalFs；WebDAV stream 取消硬实时性仍受既有 CT17 边界约束。
- HTTP Server/ACL/部署、Dreaming、Backup、完整 M6 验收与 CT22/CM5 未启动。

## 10. 最终状态

**提交 Lead 初验，CM4 未 DONE，CT22/CM5 未启动。**

真实模型 not-run；无 Git 写操作。
