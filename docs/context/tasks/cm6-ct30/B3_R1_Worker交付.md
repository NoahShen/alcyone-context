# CT30 B3 R1 Worker 交付

## 本次修改（2026-11-12，补写缺失的 Worker 交付证据）

- 本次仅新增本交付文档，汇总 Worker 已在旧生产上取得的真实红例、最小身份修复后的定向与 storage 模块回归、统一 XML 对账、macOS arm64 目录同步探测和最终文件 SHA。
- 未修改生产或测试代码，未运行代码或测试，未执行 Git 写操作。生产与测试文件继续保持冻结，B3 仍为 `IN_REVIEW / R1`，等待 Lead 独立初验并执行一次最终 `./scripts/check`。

## 1. 写集合与最终 SHA-256

本轮生产/测试精确写集合为以下三个文件；本交付文档是随后获准补写的唯一新增文件，不代表恢复代码写入。

| 文件 | 角色 | 最终 SHA-256 |
|---|---|---|
| `vfs/storage/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsStorage.kt` | 唯一生产文件 | `b4b5e45d32ba724426e758fd39b7784b741a8680b6f72905236550bdb2e07215` |
| `vfs/storage/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsPublishNewTest.kt` | publishNew 身份/故障测试 | `acf039418b7ae2036c51bce43afedd4f7272f4ff12cd3b9dd795853b609d4da1` |
| `vfs/storage/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/storage/opendal/LocalFsSafeReplaceTest.kt` | 默认目录能力/既有 safeReplace 回归 | `f45695f8f26771f303528e12bc37ef0ee48db5fd851ad28846a5681a456c22ff` |

生产文件在修复前的 SHA-256 为：

```text
916c87c8592476623ec445774f5348e1835b50de7465a1a0e90466b911deb380
```

修复后的最终 SHA-256 为：

```text
b4b5e45d32ba724426e758fd39b7784b741a8680b6f72905236550bdb2e07215
```

## 2. 旧生产上的权威红例

先在修复前生产 SHA `916c87c8…` 上提交真实 Local FS 路径替换测试。测试在受控 `InputStream.read` 中移动本次 staging、在原子名写入不同内容的普通文件，再分别抛 `IOException` 与 `CancellationException`；未使用并发 sleep 竞态。

精确命令：

```bash
./scripts/dev gradle :vfs:storage:test --tests com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsPublishNewTest --rerun-tasks --console=plain
```

运行窗口为 `2026-11-12T05:57:50Z`～`2026-11-12T05:57:59Z`，退出码为 **1**。日志同时记录 `old_production_sha=916c87c8592476623ec445774f5348e1835b50de7465a1a0e90466b911deb380`。

权威 XML 为 `tests="10" skipped="0" failures="2" errors="0"`，两个失败断言如下：

1. `replacement staging path survives IOException without publishing()`：`MultipleFailuresError` / `NoSuchFileException`。旧实现删除了替换后的普通文件，导致断言读取原子 staging 路径时该路径已不存在；目标未被发布。
2. `replacement staging path survives cancellation without publishing()`：`DefaultMultiCauseException`，包含同一 `NoSuchFileException` 和 `AssertionFailedError: Expected value to be true.`。前者证明替换文件被删除，后者对应当前 `CancellationException` 原样传播断言未成立。

证据文件：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.command
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.exit
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.stdout
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.stderr
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.failure.xml
/private/tmp/ct30-b3-r1-worker.bOADCm/red-publish-authoritative.xml-time
```

## 3. Spotless 格式任务

最终生产/测试源码上的格式命令：

```bash
./scripts/dev gradle :vfs:storage:spotlessApply --console=plain
```

运行窗口为 `2026-11-12T05:59:29Z`～`2026-11-12T05:59:32Z`，**EXIT=0**，`BUILD SUCCESSFUL in 2s`。独立退出码文件内容为 `0`。

证据文件：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/spotless-storage.command
/private/tmp/ct30-b3-r1-worker.bOADCm/spotless-storage.exit
/private/tmp/ct30-b3-r1-worker.bOADCm/spotless-storage.stdout
/private/tmp/ct30-b3-r1-worker.bOADCm/spotless-storage.stderr
```

## 4. 最小修复与定向绿测

生产修复只涉及 `LocalFsStorage.kt` 的临时文件身份边界：`CREATE_NEW` 后、首次输入读取和 `force(true)` 前记录非空 `fileKey`；初始 key 读取失败或为 `null` 时禁止发布并保留身份未知的 staging；`sameTemp` 对 expected/actual `null` 均返回不匹配；只有已知且当前身份一致时才清理。未新增公开 API、配置或 strict mode。

最终源码上的定向命令：

```bash
./scripts/dev gradle :vfs:storage:test --tests com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsPublishNewTest --tests com.github.noahshen.alcyone.context.vfs.storage.opendal.LocalFsSafeReplaceTest --rerun-tasks --console=plain
```

运行窗口为 `2026-11-12T06:04:51Z`～`2026-11-12T06:05:13Z`，退出码为 **0**，统一结果 **28/28 passed**：

| XML | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `LocalFsPublishNewTest` | 13 | 0 | 0 | 0 |
| `LocalFsSafeReplaceTest` | 15 | 0 | 0 | 0 |
| 合计 | **28** | **0** | **0** | **0** |

新增身份用例覆盖：真实路径替换后的 IOException、CancellationException 原样传播、初始 fileKey 为 `null`、fileKey 读取抛错、清理复核得到 `null`；均断言零错误发布、未知/替换路径保留、输入恰好关闭一次及既有失败语义不降级。

证据文件：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/green-fixed-test-classes-scope-final.command
/private/tmp/ct30-b3-r1-worker.bOADCm/green-fixed-test-classes-scope-final.exit
/private/tmp/ct30-b3-r1-worker.bOADCm/green-fixed-test-classes-scope-final.stdout
/private/tmp/ct30-b3-r1-worker.bOADCm/green-fixed-test-classes-scope-final.stderr
/private/tmp/ct30-b3-r1-worker.bOADCm/final-scope-publish.xml
/private/tmp/ct30-b3-r1-worker.bOADCm/final-scope-safe-replace.xml
```

## 5. storage 模块与统一 XML 对账

最终源码上的 storage 模块命令：

```bash
./scripts/dev gradle :vfs:storage:check --rerun-tasks --console=plain
```

运行窗口为 `2026-11-12T06:05:20Z`～`2026-11-12T06:06:10Z`，退出码为 **0**，`BUILD SUCCESSFUL in 49s`。

修复后当前源码的 `@Test` 计数为 **184**，与本次 fresh storage check 的 XML 聚合数一致。两套历史数字必须按不同时点和来源分别记账：

| 时点 | 来源 | XML 文件 | tests | failures | errors | skipped |
|---|---|---:|---:|---:|---:|---:|
| R1 修复前历史基线 | 旧 Lead 文件 `docs/context/tasks/cm6-ct30/B3_Lead初验.md` 的磁盘 XML 表格，不是本次重跑 | 20 | **178** | 0 | 0 | 0 |
| 本次修复后 fresh storage check | `:vfs:storage:check --rerun-tasks` 当次生成的 Gradle XML 聚合 | 20 | **184** | 0 | 0 | 0 |

因此两个总测试数的算术差为 `184 - 178 = 6`；但旧 178 的逐类 XML 没有保存在本任务目录，当前不能仅凭现存证据严谨断言这 6 例分别落在哪两个测试类，标记为**待 Lead 复核**，不猜分配。修复后已持久化的两份目标 XML 明确为 `LocalFsPublishNewTest 13 + LocalFsSafeReplaceTest 15 = 28`，均为 `0 failures / 0 errors / 0 skipped`。

本次 fresh storage check 的具体 XML 来源路径如下：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/final-scope-publish.xml
/private/tmp/ct30-b3-r1-worker.bOADCm/final-scope-safe-replace.xml
/Users/noahshen/workspace/alcyone-context/vfs/storage/build/test-results/test/TEST-*.xml
```

前两项是在 storage check 时间窗内复制并持久化的两个目标 testsuite；第三项是当次 `20` 个 testsuite、`184` tests 的 Gradle 临时聚合目录。该临时目录随后被新的定向按类输出覆盖，因此当前磁盘不再保留完整的 20 文件快照；不得用当前目录反推当次 184。旧 Lead 文档中的 178/20 也不得与本次 fresh 的 184/20 混写为未经说明的“前后”结论。

证据文件：

```text
/private/tmp/ct30-b3-r1-worker.bOADCm/storage-check-scope-final.command
/private/tmp/ct30-b3-r1-worker.bOADCm/storage-check-scope-final.exit
/private/tmp/ct30-b3-r1-worker.bOADCm/storage-check-scope-final.stdout
/private/tmp/ct30-b3-r1-worker.bOADCm/storage-check-scope-final.stderr
```

## 6. 默认 LocalFs 目录同步能力

`LocalFsSafeReplaceTest.default create directory sync capability matches a real directory force probe()` 未注入 `directorySyncer`，而是独立对真实临时挂载根执行目录 `open + force`，再与默认 `LocalFsStorage.create` 的 capability 比较。当前验收环境输出为：

```text
LocalFs directory sync probe: os=Mac OS X arch=aarch64 actual=true capability=true
```

该断言是条件化的：仅在真实目录探测成功时硬断言 capability 为 true，在探测不支持的平台保留实际 false，不通过 skip 凑数，也不把 macOS 结果外推到 Linux。

## 7. 未运行项与冻结状态

以下未由 Worker 运行或声明通过：

- 最终 14 模块 `./scripts/check`：按 R1 分工由 Lead 只执行一次，保留 stdout/stderr、退出码、源码 SHA 与 14 模块 XML 对账。
- 真实断电、内核崩溃、Linux 目录 flush、硬件 flush 与生产部署。
- 真实 WebDAV/远程服务；本轮未改变 WebDAV capability 或拒绝语义。
- 默认 Runtime/API、自动恢复、可靠消费、B4、CT31、CT32。

本轮没有 Git 写操作，未执行 `reset`、`checkout` 或 `clean`。生产与测试代码现再次冻结，Worker 不继续运行测试或调整代码；B3 仍为 `IN_REVIEW / R1`，等待 Lead 独立初验及最终全量后提交 Architect。
