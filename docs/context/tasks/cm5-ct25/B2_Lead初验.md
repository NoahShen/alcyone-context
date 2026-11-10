# CT25 B2 · Lead 独立初验

**结论**：**LEAD ACCEPTED；提交 Architect 最终复核。CT25 整体未 DONE，CT26 LOCKED。**

## 1. 实现与矩阵复核

| 验收项 | Lead 证据 |
|---|---|
| 唯一入口/范围防御 | 生产、fake、adapter 仅 `process(DreamingBatchContext)`；空、重复、降序、first<=expected、last>U、负值拒绝；空洞 ID 接受。 |
| 三批真实 SQLite | 含空洞 ID 的 1/3/5 三批；expected=0/1/3，U=5；回调读取 checkpoint=1/3/5；最终 Raw/Pending 各 3。 |
| 批间不重读 | Tracking Dreaming Ops 的普通 checkpoint read=1；Committer 事务内 CAS 独立保留。 |
| 固定 U/下一 run | 当前 run 排除高于 U 的 Turn；下一 run 从数据库水位继续。 |
| 顺序/通知/真实计数 | Evidence→Reflection→Commit→wakeup；每 rawCount>0 批一次；0 Topic/0 Insight 无通知但 CAS 推进。 |
| 通知失败 | 普通异常固定脱敏 warning，提交保留、后续批继续；指定 CE 同一实例传播，partial 不含本批，同 Coordinator 下一 run 从权威水位恢复且不重复。 |
| 失败矩阵 | Evidence、Reflection、真实 Commit SQLite 回滚；普通错误映射 BATCH_PROCESSING，前批 Raw/Pending/Checkpoint/partial 保留，后续不执行。 |
| 预算与真实组件 | 真实 Discovery 预算将 3 Turn 拆 3 批；空 Organized 跳过检索；真实 Evidence/Reflection/Committer 落 3 Raw(DREAMING)+3 Pending，checkpoint=3。 |
| 既有回归 | CT22 分页/预算/关闭/取消/partial、CT23 Topic、CT24 Evidence、B1 Committer 均重跑通过。 |

补充预算测试最初误建独立测试文件，已并入白名单内 `LlmDreamingBatchProcessorTest.kt` 并删除额外文件；最终路径集合符合白名单，未保留越权文件。

## 2. Lead 独立执行

以下命令均由 Lead 在本工作区实际执行，exit 0：

```text
./scripts/dev gradle :context:runtime:test --tests '*dreaming*' :context:core:test --tests '*dreaming*' :context:persistence-sqldelight:test --console=plain
./scripts/dev gradle :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks --console=plain
./scripts/dev gradle :context:core:spotlessCheck :context:runtime:spotlessCheck :context:persistence-sqldelight:spotlessCheck --rerun-tasks --console=plain
./scripts/check
git diff --check
```

- 三模块强制测试：BUILD SUCCESSFUL，29 tasks executed。
- 三模块 Spotless：BUILD SUCCESSFUL，13 tasks executed。
- `./scripts/check`：BUILD SUCCESSFUL，131 actionable tasks executed，14 模块 clean build。
- 14 模块最终 XML：**2115 tests / 0 failures / 0 errors / 0 skipped**。
- 源码注解行首统计与 XML 为 **2115=2115**；朴素全文匹配多出的 1 是 `B3Fixtures.kt` 注释中的字面量 `@Test`，不是测试方法。

## 3. 边界与未执行项

- 最终 B2 生产/测试路径仅为白名单 4+4；无 B1 Reflection/Committer、CT24、Persistence 生产、Schema、VFS/common/Gradle/公共 API/Factory/Impl/Cron 修改。
- `ContextSchema.sq` SHA-256：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。
- Dreaming core 无 Ktor/kotlinx.serialization 导入。
- 真实模型 not-run；独立子进程重启、固定集语义评测 not-run；无 Git 写操作。

**建议 Architect 复核 B2 后裁定 CT25 整体状态；Lead 不自行批准最终 DONE，也不启动 CT26。**

## 4. R1 补测独立初验（2026-11-10 01:17 UTC）

依据 Architect `B2_Architect复核.md` R1，Worker 已补测并完成干净全量；Lead 仅审查和复验，未修改生产/测试代码。

| R1 项 | Lead 独立证据 |
| --- | --- |
| 普通通知异常 | `ordinary notification failure is sanitized...` 注入敏感异常；固定 WARN、无 Throwable/敏感正文；首批提交保留，第二批继续；最终 Raw/Pending=2/2、checkpoint=2。 |
| Evidence/Reflection 零副作用 | 第二、三批各只执行到指定失败点；前批 Raw/Pending/checkpoint/partial 保留，当前批无新写，通知只计前批一次，第三批未执行。 |
| 真实 SQLite Commit 失败 | 真实 `DreamingCommitter` 使用故障 SQLite delegate；第二批 CAS 时抛 `DreamingCommitException`，Coordinator 映射 BATCH_PROCESSING；前批完整保留，当前批 0 新写/0 通知，第三批不执行。 |
| 非空 Topic/Evidence 0 Insight | Reflection 收到非空 Topic/Turn 后合法 emptyList；真实 CAS 逐批 0→1→2→3，0 Raw/Pending/0 通知，固定 U=3 与后续范围正确。 |

Lead 实际串行执行：

```text
./scripts/dev gradle :context:runtime:test --tests 'com.github.noahshen.alcyone.context.memory.runtime.dreaming.LlmDreamingBatchProcessorTest' --rerun-tasks --console=plain
```

exit 0，25/25 tasks executed，20s；XML `tests=10, failures=0, errors=0, skipped=0`，timestamp `2026-11-10T01:17:03.542Z`。

Worker 干净 `./scripts/check` exit 0，131/131 tasks，2m23s；14 模块 **2118=2118**，fail/error/skip=0/0/0。此数取代前文 B2 首轮的 2115；相对 B1 2113 恰增 5。早期并行 clean 导致的一次输出缺失不计为代码失败，后续已单线程干净复验。

Lead 复算 B2 四生产 SHA 与 Worker 交付一致；B1 `DreamingCommitter` SHA `bb8380a1…2bc8b9`、Schema SHA `ec727a53…aadd8ea3` 未变；`git diff --check` exit 0。未发现需修改生产的缺陷。真实模型 not-run，CT26 LOCKED，无 Git 写操作。

**R1 初验通过，现提交现有 Architect `1e2eb7c4-6fbd-4f26-8a55-b2b819618f37` 复核关闭 R1；Lead 不自行标 DONE。**

