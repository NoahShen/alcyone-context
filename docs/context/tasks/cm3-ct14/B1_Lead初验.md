# CT14 B1 · Lead 独立初验（Governance DTO/Validator/Prompt/Service）

**日期**：2026-11-01
**初验人**：CM3 Lead（只读初验）
**结论**：**B1 APPROVED / DONE（无 R 项）；CT14 APPROVED / DONE（2026-11-01）；CT15 按用户暂停指令不启动。**
**依据**：[B1 委派单](B1_委派单.md)、[Architect 裁定](Architect_裁定.md)、[CT14 任务书](../../milestones/cm3/CT14_任务书.md)

## 0. 本次修改（2026-11-01，B1 初验记录）

Worker 完成 12 个白名单文件、60 例新测试；Lead 独立复算 SHA、检查源码/测试、实跑定向与全量、复核边界与 CT09 偶发超时。未发现 CT14 阻断项；B1 未接生产、无 git。

## 1. 交付面与 SHA

| 文件 | SHA-256 |
| --- | --- |
| Core `GovernanceAction.kt` | `1f018063…caaa776` |
| Core `GovernanceDecision.kt` | `1f87cfc3…0b82fa4` |
| Core `GovernanceDecisionValidator.kt` | `74dc0e87…ceb64b3` |
| Core `GovernancePrompt.kt` | `9ca6111e…f0472748` |
| `organization-governance-v1.md` | `bce0dc53…280c7d92` |
| `organization-governance-schema-v1.json` | `5fe5baf0…ddad384` |
| Runtime `LlmGovernanceService.kt` | `16b40264…d4e2f3b` |
| Runtime `JsonGovernanceResponseParser.kt` | `dd857fa3…40bf87a8` |
| Core `GovernanceDecisionValidatorTest` | `955021e8…71a947` |
| Core `GovernancePromptTest` | `a241b130…283b539` |
| Runtime `JsonGovernanceResponseParserTest` | `9e20f4c1…29c277c` |
| Runtime `LlmGovernanceServiceTest` | `506fbb8b…f0223742` |

- CT13 6 个源码/资源 SHA 与终态一致；Factory/Impl/Config/Loader/SQL/Schema/索引/VFS/common/构建未改；无公开 API/配置变更。
- 未接生产：`AlcyoneContextImpl/Factory/ContextConfig/Loader` 无 Governance 引用。

## 2. 独立全量

| 命令 | 结果 |
| --- | --- |
| `./scripts/check` | **exit 0**，`BUILD SUCCESSFUL in 2m 23s`，131 tasks |
| 14 模块行首 `@Test` vs XML | **1558 = 1558**，差额 0，失败/错误/跳过 0 |
| CT14 定向（core/runtime `*Governance*` + Service）连续 3 次 | 均 exit 0（9/10/9s） |

| 模块 | 源码 | XML | 模块 | 源码 | XML |
| --- | ---: | ---: | --- | ---: | ---: |
| common | 23 | 23 | integration-tests/vfs | 107 | 107 |
| context/api | 26 | 26 | vfs/api | 66 | 66 |
| **context/core** | **93** | **93** | vfs/core | 305 | 305 |
| persistence | 176 | 176 | vfs/git-backup | 73 | 73 |
| **context/runtime** | **374** | **374** | vfs/persistence | 52 | 52 |
| context/server | 30 | 30 | vfs/runtime | 61 | 61 |
| integration-context | 18 | 18 | vfs/storage | 154 | 154 |
| **合计** | **1558** | **1558** | | | |

新增 60：Core 34（Validator 19 + Prompt 15）、Runtime 26（Parser 7 + Service 19）。

## 3. A01～A08 映射

| # | 关键测试/证据 | 断言 | XML |
| --- | --- | --- | --- |
| A01 | Validator NEW 8 例；Service `a valid new decision...` | content/domains 必填、target 缺席；空候选 NEW；合法返回且 DB 0 写 | Validator 19/19；Service 19/19 |
| A02 | Validator MERGE 6 例；Service `a valid merge decision...` | target 候选；content 可选；domains 空数组/非空拒绝；不降 NEW | 同上 |
| A03 | Validator UPDATE 5 例；Service `a valid update decision...` | target+content 必填；domains 可选/去重；DB 0 写 | 同上 |
| A04 | `a target outside...`、`a forged target...`、`an invalid merge...`、`an invalid merge never degrades...` | 越权/伪造 target、非法组合 → `INVALID_RESPONSE`；不降 NEW、不写库 | Service/Validator |
| A05 | `the system prompt prioritises fact time...`、`routes backfilled...`、`routes later...`、`requires explicit uncertainty...`、`fixed samples...` | 六要素；旧事实 MERGE、后续 UPDATE、矛盾/未确认、非法降级反例 | Prompt 15/15；Service |
| A06 | `budget overrun...`、`output reserve...`、`exact budget...`、`invalid estimator...` | 超限 0 LLM；输出/余量边界；等号允许；非法估算 INVALID_REQUEST | Service |
| A07 | `every candidate...single request`、`empty candidate set...`、Parser 7 例 | 全部候选单请求；空候选 NEW；严格 JSON/无围栏/未知字段拒绝 | Service/Parser |
| A08 | `./scripts/check`、1498→1558、81、Core/Schema、Prompt/Schema | 全量/边界；Core 无 Ktor/serialization；Schema 未变；未接生产 | Lead 全量 |

## 4. 边界

| 项 | 结果 |
| --- | --- |
| 81 VFS 基线 | `Checked 81 files: all OK` |
| `vfs`/`integration-tests/vfs` status/diff | 空 |
| Core 无 Ktor/serialization | build/import 无命中 |
| `ContextSchema.sq` | `ec727a53…` 未变 |
| CT13 组件 | 6 个源码/资源 SHA 未变 |
| 生产接线 | 无 Governance 引用；未改 Factory/Impl/Config/Loader |
| 依赖/Schema | 无新增 |

## 5. 非阻断观察

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | Worker 首轮 `ExtractionPipelineLifecycleTest > disabling idle...` 偶发 10s timeout | 既有 CT09 测试，不在 CT14 白名单；Lead 连续 5 次定向 + 全量 check 均通过；CT14 未改该文件。属既有测试稳定性观察，需后续单独评估，不阻断 CT14 |
| L2 | Service 取消测试用 `while (llm.calls == 0) Thread.sleep(1)` 等待进入挂起点 | 有界于 Fake 同步递增，实际稳定通过；建议后续可换成信号/超时，但非本批阻断 |
| L3 | Prompt 候选 domains 以逗号拼接进属性，未做属性转义 | 程序级 target 权限由 Validator 集合校验；Prompt 明确正文为数据；未发现越权路径 |
| L4 | Service 不持有 DB；真实 SQLite 用于种子/0 写断言 | 符合 D1 组件边界；CT15 才做提交 |

## 6. 结论

- 12 文件、60 例新增；全量 1558=1558；Core/Runtime 定向 3 次通过；81/Core/Schema/CT13 边界通过；生产零接线。
- **B1/CT14 已 APPROVED / DONE（无 R 项）**；CT15 按用户暂停未启动。

### 证据

- `.local/validation/cm3/lead/ct14-b1/check.log`、`test-counts.txt`、`ct14-repeat-1..3.log`、`pipeline-1..5.log`、`runtime-full.log`、`vfs-baseline.txt`、`vfs-git.txt`、`core-boundary.txt`、`schema-sha.txt`、`ct13-sha.txt`
- Worker：`.local/validation/cm3/worker/ct14-b1/`
