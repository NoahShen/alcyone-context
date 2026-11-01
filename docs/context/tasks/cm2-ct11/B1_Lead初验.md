# CT11 B1 · Lead 独立初验（Validator / 来源映射 / 原子提交）

**日期**：2026-10-30
**初验人**：CM2 Lead（只读初验，不写生产代码）
**结论**：**B1 Lead 初验通过，提交 Architect 独立复核**；B2 在复核通过前不启动。
**依据**：[B1_委派单](B1_委派单.md)、[CT11 任务书](../../milestones/cm2/CT11_任务书.md) §0.1、[CT11 方案核对](CT11_方案核对与待裁定.md)

## 0. 本次修改（2026-10-30，B1 初验记录）

Worker 完成 CT11 B1（仅新增 7 文件）；Lead 独立复算 SHA、跑三组命令、核对 Validator/来源映射/Committer/JSON 严格性，并确认 Core 无 serialization、CT10/CT09 SHA 未变。**全部通过**。

## 1. 交付面与边界

本批仅新增 7 文件；CT10/CT09/SQL/Schema 未变：

| 文件 | SHA-256（Lead 复算 = Worker） |
| --- | --- |
| `core/.../extraction/ExtractionCandidate.kt` | `780c1d7f…4309eab6` |
| `core/.../extraction/ExtractionResultValidator.kt` | `d5e8165e…f1835163` |
| `core/.../extraction/ExtractionCommitter.kt` | `63049b75…0d6a3042` |
| `core/src/test/.../ExtractionResultValidatorTest.kt` | `d37affc4…5028090a` |
| `runtime/.../extraction/JsonExtractionResultValidator.kt` | `2467721c…f8343dc9` |
| `runtime/src/test/.../JsonExtractionResultValidatorTest.kt` | `1649c94f…8cae74e02` |
| `runtime/src/test/.../ExtractionCommitterTest.kt` | `79703d4e…88ab921e` |

- CT10 Builder/BuilderTest、Core Prompt/资源/Context/Result、Config/Loader/BudgetPolicy、CT09 Scheduler/Pipeline/Impl/Factory、`ContextSchema.sq` SHA 未变。
- `context/core/build.gradle.kts` 无 serialization/ktor；无新依赖；`spotlessApply` 后 7 SHA 不变；无 git。

## 2. Lead 独立实跑与计数

| 命令 | 结果 |
| --- | --- |
| `./scripts/dev gradle spotlessApply --console=plain` | BUILD SUCCESSFUL，SHA 不变 |
| `./scripts/dev gradle :context:core:test :context:runtime:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |
| `./scripts/dev gradle :context:persistence-sqldelight:test :context:server:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL |

| 模块 | 源码 | XML | 差额 | 失败/错误/跳过 |
| --- | ---: | ---: | ---: | --- |
| core | 43 | 43 | 0 | 0/0/0 |
| runtime | 288 | 288 | 0 | 0/0/0 |
| persistence-sqldelight | 176 | 176 | 0 | 0/0/0 |
| server | 30 | 30 | 0 | 0/0/0 |

core 34→43（+9）、runtime 275→288（+13）。

## 3. Validator / 来源映射核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| DTO（Candidate/Memory/Validated）字段固定；来源映射 `ConversationTurnRef` | 源码 | ✔ |
| memories 可空 → 空列表成功 | Core 用例 | ✔ |
| content trim 非空（原样保留）、source 非空 | Core 用例 | ✔ |
| 来源 ID 必须在 Snapshot；映射四元业务键 | Core 用例 | ✔ |
| 重复来源按首次去重 | Core 用例 | ✔ |
| 整批至少一个 Snapshot PENDING Target；仅 History/跨 Session/窗口外拒绝 | Core 用例（按 D6 整批口径） | ✔ |
| 非法整批拒绝、消息不含正文/cause | Core 用例 | ✔ |
| Runtime JSON：object/仅 memories/元素仅两字段/整数/非空/未知字段拒绝 | JSON 用例（19 类非法载荷） | ✔ |
| JSON 非法/围栏 → `INVALID_RESPONSE`、无 content/cause | JSON 用例 | ✔ |

## 4. 原子提交核对

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 单 `database.write`；targets=PENDING 非空 | 源码 | ✔ |
| 每批生成 Raw：R-MEM ID、EXTRACTION、ConversationTurnRef、同批 createdAt | 源码 + Committer 用例 | ✔ |
| 预检 ID → insertBatch 行数 → enqueue 每条 1 → markComplete 汇总==Target | 源码 + 用例 | ✔ |
| 空 memories 只 complete，无 Raw/Pending | 用例 | ✔ |
| Target 状态变化 → 整笔回滚、0 新 Raw | 用例 | ✔ |
| ID 碰撞仅预检命中重试整批 ≤3；已有 Raw 不覆盖；耗尽失败 0 新 Raw | 用例（Fake IdGenerator） | ✔ |
| 不通知/不写 failed/CE/DB 错误传播 | 源码 + 用例 | ✔ |
| 无部分写、行数不符回滚 | 源码 + 用例 | ✔ |

## 5. 新增测试（22 例）

- Core Validator 9：Target/History、首次去重、空 memories、content/source 非法、Snapshot 外/跨 Session、仅 History、整批含 Target、正文不泄漏。
- Runtime JSON 5（覆盖 19 种非法载荷）：合法/空、未知字段、缺 memories、content/source 非法、外部/History、非法 JSON/围栏。
- Runtime Committer 8（真实 SQLite）：非空成功、空 memories、Target 变化回滚、ID 碰撞重试/耗尽、无 Pending、CE、无 Notifier。

## 6. 非阻断观察

| # | 项 | 裁定 |
| --- | --- | --- |
| L1 | Validator 按 Architect D6 的**整批**“至少一个 PENDING Target”口径，而非设计 §5.7 逐条措辞 | 按最新裁定执行；若未来改逐条，需另行评审 |
| L2 | Committer 状态冲突/行数不符抛 `ExtractionCommitException`（IllegalStateException），B2 需按 D10 传播/记录 | B2 接线时确认日志/错误分类 |
| L3 | ID 碰撞依赖事务内 `findById` 预检；持久化层不暴露主键冲突码 | 符合 D8；预检覆盖已知碰撞 |
| L4 | Core 无 serialization；JSON 严格性在 Runtime 实现 | 符合 D1；Core build 未变 |
| L5 | `MemoryIdGenerator` 由调用方注入，B2 需传 `MemoryIdGeneratorImpl` | 接线细节留 B2 |

## 7. 结论与下一步

- B1 DTO/Validator/来源映射/原子提交/Runtime JSON 均按 D1/D6/D7/D8/D10/D11/D12 落实；22 例新增 + 四模块计数 43/288/176/30 差额 0；Core 无 serialization；CT10/CT09 未变。
- **B1 Lead 初验通过，提交 Architect 独立复核**；B2 在复核通过前不启动，CT12/CT13 不启动。

### 证据路径

- `.local/validation/cm2/lead/ct11-b1/spotlessApply.log`、`core-runtime-test.log`、`persistence-server-test.log`
- Worker：`.local/validation/cm2/worker/ct11-b1/`
