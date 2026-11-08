# CT17 B2 · Architect 复核

**日期**：2026-11-08
**状态**：**B2 APPROVED / DONE；P3 CLOSED；P1/P2 接受为 CT17 残余风险**
**复核对象**：[B2 Worker 交付](B2_Worker交付.md)、[B2 Lead 初验](B2_Lead初验.md)、[B2-R1 Lead 复验](B2_R1_Lead复验.md)、B2 实际 Diff
**依据**：[B2 委派单](B2_委派单.md)、[B1 Architect 复核](B1_Architect复核.md)、[CT17 任务书](CT17_任务书.md)

## 1. 最终结论

**CT17 B2 APPROVED / DONE。**

- B2 累计严格为 11 文件白名单：4 修改 / 7 新增；白名单外 B2 变更 0。
- P3 已关闭：Resource 安全映射与关流只捕获 `Exception`；`CancellationException` 原样传播；`AssertionError`/JVM Error 原样传播。
- P1/P2 接受为 CT17 残余风险，不阻塞 DONE。
- B1 与 B2 合并边界未发现问题。
- CT17 因 B1+B2 均完成而 APPROVED / DONE，见 [CT17 收口报告](CT17_收口报告.md)。
- CT18 未解锁，不启动、不规划。

## 2. 最终证据

| 项目 | 结论 |
| --- | --- |
| B2 文件 | 11 文件：4 修改 / 7 新增；白名单外变更 0 |
| B2 新增测试 | 28 个 |
| 定向测试 | context-api/context-runtime/integration-context 通过 |
| Spotless | 三模块通过 |
| 全量回归 | `./scripts/check` exit 0，131/131 tasks |
| 14 模块计数 | source `@Test` = XML = **1710**，fail/error/skip 全 0 |
| fatal throwable | stat/read/open/stream-read/close 的 AssertionError 原样传播；XML 14=14 |
| B1 SHA | 13/13 一致 |
| 81 VFS 基线 | 75 一致、6 个 B1 批准差异、缺失 0、未批准差异 0 |
| Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3` 未变 |
| 依赖/构建/common/Factory 等 | B2 白名单外零修改 |
| Git | `git diff --check` 通过；无 Git 写操作 |

## 3. B2 生产契约复核

- `AlcyoneContext.loadMeta/read/readStream` 已实现，第三方默认路径安全拒绝。
- `ResourceMetadata(uri, sizeBytes, modifiedAt)` 为最小公开模型。
- `ContextResourceException` 只含安全 code/message，不复制 VFS cause。
- URI gate 仅允许 `/resources/<non-empty>` 文件；非法/越界 URI 零 VFS I/O。
- `read` 复用 VFS ByteArray 限额。
- `readStream` 为冷 Flow、64 KiB 分块、每次 collect 独立开关流；完成/异常/取消/提前结束均关流。
- 缺 resources mount 时 start 保持兼容，调用时返回 `UNAVAILABLE`。
- Context CREATED/STARTING/STOPPING/CLOSED 主路径错误码符合冻结契约。

## 4. P3 关闭

P3：Resource 映射与关流 catch 过宽，可能把 JVM Error 包装为 `ContextResourceException`。

**Architect 复核：CLOSED。**

- stat/read/open/stream-read/close 的安全映射 catch 已收窄为 `Exception`。
- `CancellationException` 保持原实例传播。
- `AssertionError` 与 JVM Error 不进入安全映射，原样传播。
- 新增测试真实执行 public `loadMeta/read/readStream` 的五条 fatal 路径。
- `ContextResourceAccessTest` 源码/XML 均 14=14，fatal 用例实际进入 XML。

## 5. P1/P2 残余风险

### P1：readStream 阻塞读取未切 IO dispatcher

**接受为 CT17 残余风险。**

- 不影响字节完整性、关流或功能验收；
- 调用方若在单线程/UI dispatcher collect，需自行避免阻塞；
- 后续若要求 UI-safe，另立任务。

### P2：access 取得后的精确 close 竞态返回 UNAVAILABLE

**接受为 CT17 残余风险。**

- 调用前生命周期门和已打开流回收已通过；
- 精确竞态可能返回 `UNAVAILABLE` 而非 `CLOSED`；
- 后续若要求统一跨 Context/VFS 关闭语义，另立任务。

## 6. 当前状态

- B1：**APPROVED / DONE**。
- B2：**APPROVED / DONE**。
- P3：**CLOSED**。
- P1/P2：**ACCEPTED RISK**。
- CT17：**APPROVED / DONE**。
- CT18：**LOCKED / 未启动 / 不规划**。
- 真实模型：**not-run**。
- 无 Git 写操作。
