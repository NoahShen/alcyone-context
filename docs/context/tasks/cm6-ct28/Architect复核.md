# CT28 · Architect 独立复核

## 本次修改（2026-11-11，B1 R1 关闭与最终裁定）

- **裁定：B1 APPROVED / DONE；R1 全部关闭，放行 B2。** CT28 整体仍为 IN_PROGRESS；B2 的 Admin/Health/Ready/managed 生命周期尚未实施或验收，CM6 未 DONE。
- 独立执行四套件 `--rerun-tasks`：**32/32**，27/27 tasks；`:context:server:check --rerun-tasks`：**62/62**，32/32 tasks；最终 `./scripts/check`：**BUILD SUCCESSFUL，132/132 tasks**。三条命令 exit 均为 0，XML 全部 0 failures/errors/skips。
- 最终 14 个受管模块源码行首 `@Test` 与 XML 合计 **2188=2188**，逐模块差额均为 0；旧 `B3Fixtures` 辅助注解已不在当前源码，未沿用历史差额解释。
- 证据：`.local/ct28-b1-architect-final-four-suites*`、`server-check*`、`check*`；全量 BUILD SUCCESSFUL in 2m 28s。生产九文件 SHA 与 R1 冻结基线逐项一致，Schema SHA 保持 `ec727a53…aadd8ea3`，无 Schema/Migration/构建/依赖改动。
- 契约澄清：`TYPE_MISMATCH` 的 HTTP 安全代码为 **`400 RESOURCE_TYPE_MISMATCH`**。首轮复核及 `B1_ArchitectR1补测委派.md` 中的 `INVALID_RESOURCE_TYPE` 是 Architect 文档笔误；原任务书只要求固定具名代码，正式 `B1_Architect_R1委派单.md`、生产与测试已一致。不得借该笔误修改生产。
- R1 实测关闭：真实 Runtime no-match 精确 `[]` 且模型调用数证明短路；UNAVAILABLE/CONFIGURATION 固定 503 且脱敏；缺 query/null/两组非法 UTF-8 均 400 且 SDK 零调用；Resource 五类错误 × meta/content 均验证提交前 status/code/脱敏。
- 静态边界继续通过：Server 生产无 JDBC/SQL、OpenDAL/VFS 实现或日志泄漏调用；Core/API 无 Ktor；`git diff --check` 通过。真实模型、生产部署、真实 WebDAV 原子备份及 B2 范围 not-run；CT27 保持 local-atomic-only。本轮无 Git 写操作。

## 历史修改（2026-11-11，B1 首轮独立复核）

- 独立阅读 B1 九份生产文件及 Read/Auth 测试，核对鉴权、严格 JSON、请求期限、资源流与错误映射；本轮未修改生产或测试代码，无 Git 写操作。
- 独立执行 `./scripts/dev gradle :context:server:check --rerun-tasks --console=plain`：exit 0，32 actionable tasks；Server 源码/XML **59=59**，failures/errors/skipped 均为 0。证据：`.local/ct28-b1-architect-server-check.log`、`.exit`。
- Lead 全量日志 `.local/ct28-b1-lead-check.log` 有效：`./scripts/check` exit 0、132/132、14 模块 **2185=2185**；Context Schema SHA 保持 `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，构建/依赖文件无 diff。
- 静态检查通过：Server 生产代码无 JDBC/SQL、OpenDAL 或 VFS 实现依赖；Core/API 生产代码无 Ktor；六条 Read 路由与 Ingestion 共享适配不引入 Context 生命周期所有权。
- **裁定：B1 IN_REVIEW / R1；暂不放行 B2。** 当前缺口是委派单明示验收项缺乏有效测试，尚不是已确认的生产行为缺陷。

## 1. R1 必补验收覆盖

### R1-1 · Search 无匹配及检索失败

现有真实 Runtime 测试只覆盖非空命中，Fake 永远返回单条结果；没有证明无匹配得到真正的空列表，也没有证明 `ContextRetrievalException(UNAVAILABLE/CONFIGURATION)` 映射 503 `RETRIEVAL_UNAVAILABLE` 且不泄露异常文本。

- 优先扩展 controlled model fixture，使 no-match query 返回空 `candidate_ids` / `memory_ids`，断言 HTTP 200 且结果为 `[]`。
- 用受控 Context 分别或至少一种具名失败验证 503、固定代码、零敏感文本；不得把失败转为 200 空列表。

### R1-2 · Search 必填字段及非法 UTF-8

委派单 A03 明确要求缺 `query`、`query:null`、非法 UTF-8。现有非法 JSON 用例只覆盖重复键、未知键、尾随垃圾、数值 `query` 和字符串 `domains`，不足以证明这些边界。

- 增加缺 `query`、`query:null`、截断 UTF-8 序列及孤立 `0xFF`；均断言 400 `INVALID_REQUEST` 且 SDK 调用数不增加。
- 只补测试，不借 R1 修改生产解析器。

### R1-3 · Resource 固定错误矩阵不完整

当前只有 meta `OUT_OF_SCOPE`、content `NOT_FOUND/UNAVAILABLE` 的有效 HTTP 断言；`INVALID_URI`、`TYPE_MISMATCH`、meta 的 `NOT_FOUND/UNAVAILABLE` 未形成委派单要求的固定映射证据。

- 在 meta/content 首块提交前覆盖 `INVALID_URI → 400 INVALID_URI`、`TYPE_MISMATCH → 400 RESOURCE_TYPE_MISMATCH`（首轮文字已按最终契约澄清）、`NOT_FOUND → 404 RESOURCE_NOT_FOUND`、`UNAVAILABLE → 503 RESOURCE_UNAVAILABLE`。
- 保留 OUT_OF_SCOPE 403 及后端秘密文本不泄露断言。

## 2. 已通过项与范围说明

- 三类 Key 的鉴权先于 body/query/SDK、错类 403、未授权零 SDK/SQLite 副作用；Search 精确 byte 限额、分块、严格 JSON 主体；请求期限与 SDK 自有取消；Resource 首块提交前/后错误、16 MiB 以上真实下载、客户端断开与 Flow 释放，均有实现及测试。
- 生产九文件当前 SHA 与 Lead R1 报告一致；既有 Server 测试无 tracked diff。新增 `ReadRuntimeFixture.kt`、`ServerLogCapture.kt` 超出原四份测试白名单，本轮不为此重写已工作的实现，但后续只允许在四份测试辅助/测试文件及 Lead 证据文档内定点补验。
- R1 修改集合：`ReadHttpApiTest.kt`、`ReadHttpResourceTest.kt`、`ReadRuntimeFixture.kt`、必要时 `ServerLogCapture.kt` 与 Lead 证据文档。生产九文件、Schema、其他模块、构建及 Git 均零写。
- R1 后须重跑 Server check，再由 Architect 独立复核；通过后执行最终全量 `./scripts/check`。真实模型、生产部署及 B2 Admin/Health 本轮均未验证、未放行。
