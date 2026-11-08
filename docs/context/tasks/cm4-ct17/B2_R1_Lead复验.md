# CT17 B2-R1 · Context Resource fatal throwable 修复 Lead 复验

## 本次修改（2026-11-08）

Lead 独立复验 B2-R1：核对 B2 11 文件范围、B1 13 文件 SHA、`Exception` 收窄、AssertionError 公共路径测试、3 模块定向、1710=1710 与工程边界；未修改生产/测试代码，未派发 Worker，未启动 CT18，无 Git 写操作。

**日期**：2026-11-08
**状态**：**B2-R1 Lead 复验通过；P3 CLOSED；P1/P2 仍为 Architect 已接受残余风险；建议提交 Architect 最终复核**
**依据**：[B2 委派单](B2_委派单.md)、[B2 Worker 交付](B2_Worker交付.md)、[B2 Lead 初验](B2_Lead初验.md)、[B1 Architect 复核](B1_Architect复核.md)
**基线**：B1 APPROVED / DONE；B2 初始交付 1709=1709；Schema `ec727a53…8ea3`

## 1. 结论

- **新增 R 阻断：0**
- **P3：CLOSED**
- **P1/P2：行为未修改，保持 Architect 已接受残余风险**
- B2-R1 实际修复范围与测试证据符合裁定。
- 建议提交 Architect 最终复核；**Lead 不自标 B2/CT17 DONE**，CT18 不启动。

## 2. 文件与范围

B2 累计仍严格为 [B2 委派单 §5](B2_委派单.md) 的 **11 文件**：4 修改 / 7 新增；白名单外 B2 变更 0。

B2-R1 相对 B2 初始交付只改动：

- `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccess.kt`
- `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/resource/ContextResourceAccessTest.kt`
- `docs/context/tasks/cm4-ct17/B2_Worker交付.md`（证据更新）

其余 B2 文件 SHA 与 B2 初始交付一致。Lead 独立复算：

- `ContextResourceAccess.kt`：`466c5594017d92a7c2cc16ab6a2dae219f2fe7f3207f6fd23d32f20f7f19d33f`
- `ContextResourceAccessTest.kt`：`af77827d52e920bf61fd120d13ae3bb9e4e5909085abbfad07ece66c3a3a053f`

## 3. P3 修复审查

### 3.1 stat / read / openStream

通过：

- `loadMeta` 的 `vfs.stat` catch 为 `Exception`。
- `read` 的 `vfs.read` catch 为 `Exception`。
- `readStream` 的 `vfs.openStream` catch 为 `Exception`。
- `mappedFailure` 参数类型收窄为 `Exception`。
- `CancellationException` 在映射前判断并原样抛出。
- `AssertionError` 等非 `Exception` fatal throwable 不进入安全映射，原实例传播。

### 3.2 stream read

通过：

- `InputStream.read` 的内层 catch 为 `Exception`，普通 IO/VFS 异常映射安全错误。
- 外层 `catch (Throwable)` 只用于保存 primary、原样 rethrow，并在 finally 执行关流；不把 fatal throwable 包装成 `ContextResourceException`。
- AssertionError 从流读取阶段原样传播，finally 仍执行 close。

### 3.3 close

通过：

- `opened.close()` 只捕获 `Exception` 并执行安全 close 错误映射/suppressed 规则。
- 无 primary 的 close AssertionError 原样传播。
- 普通 read primary + 普通 close failure 仍保持“主异常保留、安全 close 错误 suppressed”。

### 3.4 残余边界

P1/P2 对应代码路径未修改：

- P1：`readStream` 阻塞 read 仍未切换 IO dispatcher。
- P2：已取得 access 后 Context close 的精确竞态仍可能把 VFS `CLOSED` 映射为 UNAVAILABLE。

两者仍按 Architect 已接受残余风险记录，不属于 B2-R1 范围。

## 4. AssertionError 测试审查

新增测试 `fatal errors propagate unchanged from public resource methods and flow` 真实执行以下路径：

| 路径 | 注入 | 断言 |
| --- | --- | --- |
| public `loadMeta` | stat AssertionError | 同一实例原样抛出 |
| public `read` | read AssertionError | 同一实例原样抛出 |
| public `readStream` open | openStream AssertionError | 同一实例原样抛出 |
| public `readStream` read | InputStream.read AssertionError | 同一实例原样抛出，close 一次 |
| public `readStream` close | close AssertionError | 同一实例原样抛出 |

测试通过 `DelegatingResourceContext` 覆盖 `AlcyoneContext` 的三个公共方法，并直接委托生产 `ContextResourceAccess`；不是反射或只测私有辅助。AssertionError 未被包装为 `ContextResourceException`。

`ContextResourceAccessTest` 新增 1 例，总数 14；XML 独立核对为 **14=14**，该 fatal 用例实际进入 XML。

## 5. 独立执行证据

### 5.1 3 模块定向

命令：

```text
./scripts/dev gradle :context:api:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain
```

结果：

- 首次沙箱执行因 Gradle FileLock `SocketException: Operation not permitted` 未启动测试；
- 经沙箱外批准重跑：**exit 0**，`BUILD SUCCESSFUL in 48s`，32 tasks executed；
- 未把环境拒绝写成测试通过。

### 5.2 源码/XML 计数

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| context-api | 31 | 31 | 0 | 0 | 0 |
| context-runtime | 487 | 487 | 0 | 0 | 0 |
| integration-context | 21 | 21 | 0 | 0 | 0 |

结合 Worker 已生成的其他模块 XML，当前 14 模块合计 **1710=1710**，全部 fail/error/skip 0。

### 5.3 B1、VFS 与工程边界

- B1 Worker 交付的 13 个代码/测试 SHA：**13/13 一致**。
- 81 VFS 基线：**75 一致、6 个 B1 已批准差异、缺失 0、未批准差异 0**。
- B2/B2-R1 未新增 VFS diff；6 项差异仍仅为 B1 批准的 6 个生产文件。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- `common/`、`gradle/`、`settings.gradle.kts`、`gradle.properties`、各 `build.gradle.kts`：无 B2-R1 修改。
- Schema/SQL/Prompt、Factory/Config/Loader/Persistence/Server：无 B2-R1 白名单变更。
- `git diff --check`：exit 0，空输出。

## 6. 备注

### N1：P1/P2 保持已接受风险

本 R1 没有改变 dispatcher 或 Context/VFS close 竞态行为；Worker 交付报告已同步写明，不存在“顺带修复”或范围扩张。

### N2：未独立复跑全量 `./scripts/check`

3 模块定向、1710=1710、B1 SHA、81 基线与边界均通过；未发现需要扩大全量回归的新增 R 可疑点，因此沿用 Worker 的 `./scripts/check` exit 0 证据。

### N3：双重 fatal 优先级未新增专门用例

当前 R1 已覆盖 stat/read/open/stream-read/close 各自的 AssertionError。若 primary fatal 与 close fatal 同时发生，finally 中 close fatal 仍可能覆盖 primary；该组合不在本次裁定要求内，也未观察到普通异常路径回归。

## 7. 是否提交 Architect

**是，建议提交 Architect 最终复核。**

- P3 已关闭；
- 未发现新增 R 阻断；
- P1/P2 按 Architect 已接受残余风险保持；
- B2 仍为 **IN_REVIEW / 未 DONE**；
- CT17 仍为 **IN_PROGRESS**；
- CT18 不启动；真实模型 not-run；无 Git 写操作。
