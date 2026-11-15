# CT30 B3 接任 Lead：只做剩余初验

## 本次修改（2026-11-12，13:42 +08:00，第二次角色退化交接）

- 本文件是短交接任务书；不要全文读取历史 PROGRESS_SAVE、AGENTS 或旧 Agent 活动重建身份。你是 Lead，直接向 Architect 提交初验；不存在另一个需要你等待的 Lead。
- 用户恢复开发授权保持。当前只有 B3；B1/B2 DONE，B4/CT31/CT32 未放行。不得改生产/测试；如发现精确缺陷，先报告 Architect，由 Lead 管理 Worker 定点处理。

## 身份和交付

- Architect：`0d2080d2-8a60-4b31-93b9-759b7ad2a723`；server `srv_rWwNLOnyU5IK`。
- 唯一 Worker：`1d3edffa-658d-4f91-b3f2-6ee18da47cd0`；工作区 `wks_2aea38c9bc66e8f7`，本仓库目录。
- 上任 Lead `5cd573ad-ebe7-4eb4-b829-da43867a6812` 反复忘记角色，已归档。当前 Lead `fcf0cd10-edc0-4a25-8b73-c3d3f74c7881` 已按 codex-lead 配置启动；首先由其转达并确认 Worker 冻结。禁止联系更早已归档 Lead `a2d35693`。
- 最终交付只需 `B3_Lead初验.md`：明确 PASS/有缺口、文件 SHA、故障矩阵、测试与 not-run、提交 Architect。先产出具体初验意见，不做无目标测试循环。

## 已确认事实

- Worker 报告变更后全量 check exit 0，4m2s、137 tasks、312 XML、2316 tests、零失败/错误/跳过；源码 2318，差额2在 Server。此为 Worker 报告，需核对当前产物，不冒称 Lead/Architect实跑。
- Server 差额定位到 ManagedAlcyoneHttpServerTest：两个 runBlocking 最后表达式为 Boolean，JUnit 未发现。上任 Lead 越过 B3 写集合安排了修复，Worker 已补 Unit，并同步该未执行测试的旧 YAML 与健康路径；保留变更，禁止回退掩盖问题。
- Architect 已读取当前该文件和生产 schema/routes：server.auth.readKey/adminKey、/health/live 与生产一致。当前磁盘 Managed XML 为8/8，零失败/错误/跳过（UTC 05:39:52.863）。最后 Server 运行整体退出码/108例结果仍须收集。
- Architect 批准该已发生的验收修复作为严格单文件例外：仅两个方法显式 Unit、旧 YAML auth 字段和 /health/live 路径，保留清理/原断言。不扩生产/其他测试。此技术裁定不批准此前越范围流程，也不代表 B3验收。
- B3核心五文件从13:33至13:40 SHA未变。safeReplace14/14、publishNew8/8已存在；Worker报告storage178/178、四类integration定向9/9。完整 stdout 没有独立重定向文件，不能编造路径；已有XML和会话日志需准确归属。

## 当前 SHA 快照

| 文件简称 | SHA-256 |
| --- | --- |
| Storage.kt | f3d416af9f796ad721ed95df7d2d215d59b56e7b4c862877b7dbdf022e679353 |
| LocalFsStorage.kt | 916c87c8592476623ec445774f5348e1835b50de7465a1a0e90466b911deb380 |
| WebDavStorage.kt | 889a5a1e462880e0774ae86207ebd885cf274e828236c88fe399b12bb87307e4 |
| LocalFsSafeReplaceTest.kt | 367e77913f763a10b11132bb0fb7945c94ea2ab3e7128e4494f5cc501ded5fe7 |
| LocalFsPublishNewTest.kt | eb2100ae47d063c44a3d4a6990895ed94a1319bbaa1ac4f4dc220742cd1410a0 |
| ManagedAlcyoneHttpServerTest.kt | d54bf86bd05d4152c241731a6f86010e9ef23c918355a5f7f8130fb252cd6da4 |

## 剩余审查，按顺序完成

1. 确认 Worker 已冻结，索取已有 Server 最终结果；停止后不新派实现任务。对Worker请求使用完成回调，禁止循环轮询、查自己身份或等待虚构上级Lead。
2. 阅读 B3_委派单（约60行），审查现有生产/测试中目录同步能力true/false、rename/link后同步、发布后失败保新/effect、cleanup同步失败/suppressed矩阵。旧Lead发现 `sameTemp(expected == null)` 回退可能清理身份不明文件，仅为静态疑点，须判断可达性并给精确缺口，不能当已证实缺陷。
3. 校验14模块源码/XML和文件SHA。现有全量+Server增量结果须分开列；没有新生产改动时不要重跑全量。必要独立定向仅针对具体未解决疑点。
4. 写 B3_Lead初验.md，明确缺口也可提交 IN_REVIEW/R1；不要无限审阅直到“完美”。更新委派单顶部当前角色，提交 Architect 后结束当前轮等回复。

所有代码增量保留；禁止 Git 写操作、reset/checkout/clean、修改B1/B2/Schema/API/默认Runtime/Context生产/deploy。真实断电/Linux硬件flush为not-run。
