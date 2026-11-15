# CT30 B3 Lead 初验

## 本次修改（2026-11-12，冻结最终初验事实）

- Architect 已要求立即冻结：Worker 已 idle，本轮不再新建测试、不再调查、不再编码、不再查询 Agent，也不启动第三次全量。唯一的第二次在途 `./scripts/check` 已让它自然结束；以下按实际产物记录，不用成功结果掩盖重复执行的流程错误。
- Lead 结论：**B3 R1 初验通过，建议 Architect 复核后判 PASS；B3 尚未 DONE。** 身份缺口已有旧生产红灯、最小修复后的绿测和一次成功的 14 模块全量共同支撑；最终结论仍由 Architect 独立复核作出。
- 本轮生产/测试冻结写集合为 `LocalFsStorage.kt`、`LocalFsPublishNewTest.kt`、`LocalFsSafeReplaceTest.kt`。未改公共 API、Runtime/Context 生产、Schema/migration、B1/B2 recovery/event/cursor、WebDAV capability 或 deploy；无 Git 写操作。

## 红例：旧生产身份缺口已真实复现

修复前生产 SHA-256：

```text
916c87c8592476623ec445774f5348e1835b50de7465a1a0e90466b911deb380
```

Worker 在旧生产字节上运行 `LocalFsPublishNewTest`，保留退出码 **1**。权威 XML 为 `tests=10 skipped=0 failures=2 errors=0`，两个失败分别是：

- `replacement staging path survives IOException without publishing()`：替换后的普通文件被旧实现删除，断言读取原子 staging 路径时得到 `NoSuchFileException`；错误目标未发布。
- `replacement staging path survives cancellation without publishing()`：同样出现 `NoSuchFileException`，并伴随 `CancellationException` 原样传播断言失败。

证据目录：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/
  red-publish-authoritative.command
  red-publish-authoritative.exit              # 1
  red-publish-authoritative.stdout
  red-publish-authoritative.stderr
  red-publish-authoritative.failure.xml       # 10 tests / 2 failures / 0 errors / 0 skipped
  red-publish-authoritative.xml-time
```

## 最小修复与绿测

最小修复位于唯一生产文件 `LocalFsStorage.kt`：`CREATE_NEW` 后、首次输入读取和 `force(true)` 前记录本次 staging 的非空 `fileKey`；`sameTemp` 对 expected/actual `null` 均返回 false；初始身份为 `null` 或读取失败时禁止发布并保留未知身份 staging；只有已取得且当前仍匹配的非空身份才允许 link/cleanup。未扩成通用安全文件系统，也不声称抵御最后一次比较与 link/unlink 之间持续替换的任意外部进程。

修复后据实绿灯：

| 证据 | 结果 |
|---|---|
| `LocalFsPublishNewTest` | 13 tests / 0 failures / 0 errors / 0 skipped |
| `LocalFsSafeReplaceTest` | 15 tests / 0 failures / 0 errors / 0 skipped |
| 两测试类合计 | **28/28**，命令退出码 0 |
| `:vfs:storage:check` | 退出码 0，`BUILD SUCCESSFUL in 49s`，当次聚合 184 tests / 0 bad / 0 skipped |

证据目录：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/
  green-fixed-test-classes-scope-final.command
  green-fixed-test-classes-scope-final.exit   # 0
  green-fixed-test-classes-scope-final.stdout
  green-fixed-test-classes-scope-final.stderr
  final-scope-publish.xml                     # 13 / 0 / 0 / 0
  final-scope-safe-replace.xml                # 15 / 0 / 0 / 0
  storage-check-scope-final.command
  storage-check-scope-final.exit              # 0
  storage-check-scope-final.stdout
  storage-check-scope-final.stderr
```

默认 macOS arm64 目录能力由真实临时挂载根 `FileChannel.open(root, READ).force(true)` 探测，并与默认 `LocalFsStorage.create` 的 capability 比较；持久化输出为：

```text
LocalFs directory sync probe: os=Mac OS X arch=aarch64 actual=true capability=true
```

该输出位于 `final-scope-safe-replace.xml` 的 `system-out`。其他平台只比较实际探测结果，不无条件宣称 true，也不用 skip 凑数。

## 最终文件 SHA-256

下列 SHA 在 `IC1a3m/pre.sha256` 与 `IC1a3m/post.sha256` 中一致，并在冻结时再次核对：

| 文件 | SHA-256 |
|---|---|
| `vfs/storage/.../LocalFsStorage.kt` | `b4b5e45d32ba724426e758fd39b7784b741a8680b6f72905236550bdb2e07215` |
| `vfs/storage/.../LocalFsPublishNewTest.kt` | `acf039418b7ae2036c51bce43afedd4f7272f4ff12cd3b9dd795853b609d4da1` |
| `vfs/storage/.../LocalFsSafeReplaceTest.kt` | `f45695f8f26771f303528e12bc37ef0ee48db5fd851ad28846a5681a456c22ff` |

## 全量运行：两次均成功，第二次为重复执行

### `IC1a3m`：第一次成功全量

- 时间：`2026-11-12 14:11:41 +08:00` 至 `14:15:47 +08:00`；耗时 **4m5s**。
- 结果：`BUILD SUCCESSFUL`、**137 actionable tasks: 137 executed**、`final.exit=0`。
- 产物：`final.stdout` 17,157 bytes；`final.stderr` 512 bytes；`final.exit` 0；`pre.sha256`/`post.sha256` 三文件一致。
- 证据目录：

```text
/private/tmp/ct30-b3-r1-lead.final.IC1a3m/
  final.stdout
  final.stderr
  final.exit                 # 0
  pre.sha256
  post.sha256
```

### `LxlMeb`：Architect 冻结时在途的第二次重复全量

- `started-at-utc`：`2026-11-12T06:17:39Z`；`finished-at-utc`：`2026-11-12T06:22:00Z`；耗时 **4m20s**。
- 结果：`BUILD SUCCESSFUL`、**137 actionable tasks: 137 executed**、`check.exit=0`。
- `check.stdout` 17,669 bytes，末行为 `137 actionable tasks: 137 executed`；该目录没有单独 stderr 文件，不能虚构其内容或退出码。
- 证据目录：

```text
/private/tmp/ct30-b3-lead-final.LxlMeb/
  command                    # ./scripts/check
  started-at-utc             # 2026-11-12T06:17:39Z
  finished-at-utc            # 2026-11-12T06:22:00Z
  check.stdout               # 17,669 bytes
  check.exit                 # 0
```

第二次全量是 Lead 重复执行流程错误；它不提高验收等级，也不能抵消该流程问题。冻结后未启动第三次全量，未杀未知进程或清理任何产物。

## 当前 XML 完整性

第二次成功全量后，14 个模块的 `build/test-results/test/TEST-*.xml` 完整存在：**312 个 XML、2324 tests、0 failures、0 errors、0 skipped**。XML 时间范围为 `2026-11-12T06:17:48.508Z` 至 `06:22:00.151Z`，落在 `LxlMeb` 执行窗口内，因此当前文件是同窗全量产物，不是历史 XML 拼接。

| 模块 | XML 文件 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| common | 3 | 23 | 0 | 0 | 0 |
| context/api | 13 | 45 | 0 | 0 | 0 |
| context/core | 26 | 180 | 0 | 0 | 0 |
| context/persistence-sqldelight | 29 | 192 | 0 | 0 | 0 |
| context/runtime | 100 | 823 | 0 | 0 | 0 |
| context/server | 17 | 108 | 0 | 0 | 0 |
| integration-tests/context | 15 | 32 | 0 | 0 | 0 |
| integration-tests/vfs | 27 | 125 | 0 | 0 | 0 |
| vfs/api | 4 | 67 | 0 | 0 | 0 |
| vfs/core | 33 | 343 | 0 | 0 | 0 |
| vfs/git-backup | 7 | 73 | 0 | 0 | 0 |
| vfs/persistence | 8 | 62 | 0 | 0 | 0 |
| vfs/runtime | 10 | 67 | 0 | 0 | 0 |
| vfs/storage | 20 | 184 | 0 | 0 | 0 |
| **合计** | **312** | **2324** | **0** | **0** | **0** |

报告只把 JUnit XML tests 作为测试执行计数，不把源码 `@Test` 文本计数混作执行事实。

## 故障与边界确认

- safeReplace：rename 成功后同步目标父目录；capability true 时同步失败保新并至少 `PARTIAL`；capability false 保留既有原子非 durable 行为；cleanup unlink 后同步失败保新、不覆盖主异常。
- publishNew：hard-link 后先同步父目录再清理 staging；发布后目录同步/cleanup 失败保新并至少 `PARTIAL`；既有目标 no-clobber；未知身份 staging 保留人工核对。
- 输入关闭、清理与主异常的聚合已有用例；公开消息不包含物理临时路径。
- WebDAV 仍为 `durableDirectorySync=false`、atomic capability false 与显式拒绝；真实 WebDAV 服务 not-run。
- 真实断电、内核崩溃、Linux 目录 flush、硬件 flush、默认 Runtime/API durable strict mode、自动恢复、可靠消费、B4、CT31、CT32 均 not-run/未放行。

## 提交 Architect

请求 Architect 基于上述冻结事实独立复核：若接受旧生产红例、`b4b5e45…` 修复、28/28 定向绿测、`:vfs:storage:check` 184/184、macOS arm64 真实目录能力 true，以及 `LxlMeb` 同窗 14 模块 **312 XML / 2324 tests / 0 bad / 0 skipped**，则裁定 B3 R1 PASS/DONE。Lead 已停止后续测试、编码和 Agent 查询。
