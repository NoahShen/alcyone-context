# T34 交付与自测记录

## 本次修改（2026-10-16，Architect 收口证据口径订正）

- 直接订正清理保证与历史计数残留，不改生产代码和测试；最终结论以《T34 架构与技术复核》第10节为准。
- 有界等待指进程等待、读取线程 join 与清理等待。流 close 为同步 JDK 调用，后续 join 截止时间并不自动约束 close 本身；本机 JDK21/macOS 已验证持管道、超时与中断代表，不外推任意平台/异常内核的硬实时保证。
- cleanupFailed 仅表示本次可追踪进程或读取任务未确认退出；不能检测任意已脱离且不可追踪的子进程，也不能承诺这类进程必然触发 poisoned。


## 本次修改（2026-10-16，第三轮复核 R3/R5 定向收口）

- **R3 输出等待中断**：`markInterrupted(state)` 在全路径统一记录中断事实；`joinThread`、`waitForExit` 捕获 `InterruptedException` 时均调用它；中断后改用有限确认预算，不再等待到原截止时间；中断位在全部清理与确认结束后于 `finally` 恢复。
- **R3 读取资源确认**：新增 `requiredClose`，把“输出不完整”（`outputTruncated=true`）与“资源未确认”（`cleanupFailed=true`）解耦；`finally` 关闭三条流并再次 `joinThread` 有界确认；关闭后仍无法确认读取线程退出则 `cleanupFailed=true`；父进程退出后 `descendants` 不可见时以读取线程确认为准，不把空枚举当作已确认。
- **R5 直接证据**：新增 `large stdout and stderr are both drained`（stdout 与 stderr 各 20 万行）、`parent exit with held pipe marks output incomplete`、`interrupt during output wait after parent exit is not lost`、`reader threads exit after successful command`；所有涉及子进程的测试在 `finally` 中按记录的 PID 强制清理，不留 `sleep`/Worker。
- **R5 文档准确性**：归档实际统计命令；全工程常规源码 `@Test` 与常规 XML 均为 **799**（既有 752 + git-backup 47）；撤回 A05/A06“全部达成”的旧表述。
- 路径表已与实现及真实方法名逐项对齐，见 [PROCESS_PATHS_T34.md](PROCESS_PATHS_T34.md)。

### 本轮限制与未达成项（如实记录）

- **A05（输出并发排空与防挂起）与 A06（进程级联安全清理）不声明“全部达成”**：本轮只对“本进程及其当前可追踪 descendants + 两个读取线程”给出真实保证；对已脱离且不可追踪的子进程不作承诺；本次可追踪进程或读取任务无法确认退出时才通过 `cleanupFailed → poisoned` 阻断后续命令。
- Lead 最新初验已订正常规计数为 799/799；此前 840 混计为历史错误，以最新记录及 Architect 独立统计为准。

## 历史修改（2026-10-16，第二轮复核 R2～R5 残留缺陷修复）

- **R2 命令允许退出语义与空内容分流**：
  - `status --porcelain` 无论有无内容都应返回 0；非 0（如 128）抛 `Failed(COMMIT, COMMIT_HEAD_PROBE_FAILED)`，只有 exit=0 且 stdout 空白才是 `SkippedNoContent`。
  - `rev-parse --verify --quiet HEAD` 区分允许语义：0=有 HEAD、1=正常 unborn、其它（如 128）=命令故障 `COMMIT_HEAD_PROBE_FAILED`，保留首次提交能力同时拒绝真实错误。
  - 验收覆盖 5 场景：空初始仓库 `SkippedNoContent`、首次有内容提交成功、首次 hook 拒绝 `COMMIT_COMMIT_FAILED`、HEAD 查询 128 失败、status 查询 128 失败。
- **R3 取消语义与阶段归属**：`execute` 单独捕获 `CancellationException`/`InterruptedException` 并抛 `GitCommandFailure(stage, cancelCode, cancelled=true)`；`syncOnce` 外层区分 `GitCommandFailure` 与其它异常，普通异常记录真实阶段（`currentStage`）而非谎报 VALIDATE。
- **R3 输出完整性**：`StreamCollector` 记录 `truncated`/`ioFailed`，`ProcessResult` 新增 `outputTruncated`；`GitRepository.execute` 对 `outputTruncated` 实际拒绝（`OUTPUT_TRUNCATED`），协议输出不完整不再被当完整数据。
- **R3 资源清理**：主线程在 `finally` 关闭 `inputStream`/`errorStream`/`outputStream`；确认读取线程 join 成功，未退出即 `cleanupFailed`；descendants 枚举失败或 `isAlive` 查询失败均视为“无法确认”并 `cleanupFailed`；父进程与每个子进程分别独立有界等待；中断位在全部清理等待结束后才恢复。
- **R4 index gitlink 检查**：VALIDATE 增加 `git ls-files --stage`，发现 `160000 ` 前缀条目即拒绝 `VALIDATE_NESTED_REPO`，覆盖无 `.gitmodules` 的已暂存子模块记录。
- **R5 测试与计数**：大输出改为直接产生大量 stdout（`seq 1 200000`）；超时验证父/子真实 PID 退出；取消用真实 `interrupt()` 与 `CancellationException`；新增 `JvmGitProcessRunnerTest`（5 例）。
- 路径表见 [PROCESS_PATHS_T34.md](PROCESS_PATHS_T34.md)。

## 历史修改（2026-10-16，落实复核修复 R1～R5）

- **R1 日志脱敏与受控阶段错误摘要**：
  - 新增 `GitSecurity.sanitizeUrl`，将 URL 密码部分替换为 `***`（替换结果只拼 scheme、user、host，绝不拼回密码分组）。
  - 新增 `GitSecurityError` 受控错误码枚举；`GitRepository` 的所有失败路径（VALIDATE / STAGE / COMMIT / PUSH）只向结果与日志输出受控错误码，绝不回显原始 stderr、hook 输出、完整异常或未脱敏 URL。
  - `GitRepository` 支持注入 `Logger`，测试用内存 Logger 捕获真实日志，断言合成密码 `T34_SYNTHETIC_PASSWORD` 与原始 URL 均不出现在任何日志中。
- **R2 空内容与提交失败正确分流**：
  - 不再硬编码 SHA-1 空树值；unborn 分支先执行 `git status --porcelain` 判定是否存在待提交内容，空白才返回 `SkippedNoContent`。
  - 有内容时执行 `git commit`；commit 因 pre-commit hook 失败（exit 1）等被明确归类为 `Failed(COMMIT)`，不再误判为 `SkippedNoContent`。
  - 所有查询类命令（`rev-parse --verify --quiet HEAD` 等）统一处理清理失败/取消/超时，不盲目假设不存在。
- **R3 取消语义、双流截止时间、进程树清理与失效阻断**：
  - `ProcessResult` 新增 `cancelled`；线程中断/取消时保持取消语义并中止后续阶段，`SyncResult.Failed.cancelled = true`。
  - `JvmGitProcessRunner` 的进程等待与 stdout/stderr 读取共用同一截止时间；读取使用 daemon 线程并在流关闭后退出，不在进程结束后额外盲等。
  - 超时/取消时级联清理本进程及 descendants（先 `destroy` 后 `destroyForcibly`）；无法确认退出则 `cleanupFailed = true`。
  - `GitRepository` 一旦出现 `cleanupFailed` 即标记 `poisoned`，本轮与后续 `syncOnce` 直接返回 `Failed(PROCESS_CLEANUP_FAILED)`，不再发起任何新命令。
- **R4 唯一推送目标与工作区全面预检**：
  - 唯一 push 目标改用 `git remote get-url --push --all <remoteName>`；解析出 0 个或多个 URL 均拒绝（覆盖多 url 与多 pushurl）。
  - linked worktree：`root/.git` 为普通文件，或 `--git-dir` 与 `--git-common-dir` 不一致时拒绝；主工作区本身不被误拒。
  - 嵌套仓库：扫描实际目录结构，任一非根子目录含 `.git`（文件或目录），或存在 `.gitmodules` 时拒绝；校验全部发生在 add/commit/push 之前且不修改文件。
  - 生产环境变量收敛：仅清理 `GIT_DIR` / `GIT_WORK_TREE` / `GIT_INDEX_FILE`，保留宿主作者/提交身份变量，不强制 `GIT_CONFIG_NOSYSTEM`；非交互仅设 `GIT_TERMINAL_PROMPT=0` 与默认 `GIT_SSH_COMMAND`。测试隔离通过隔离 Runner 注入 `HOME` / `GIT_CONFIG_GLOBAL`。
- **R5 测试与交付记录**：
  - 新增 `GitSecurityTest`、`GitBackupLogCaptureTest`，扩充 `GitRepositoryTest`；模块测试 25 → **35** 个，全部通过。

## 1. 目标与实现

- **模块名称**: `:vfs:git-backup`
- **任务目标**: 实现内部 `GitRepository` 与 `GitProcessRunner`/`JvmGitProcessRunner`，提供单次完整同步流程（验证、暂存、按需提交、推送）、日志、超时/取消、输出并发排空与安全清理。
- **范围遵循**: 不引入通用 Git 框架或外部无关依赖，仅使用 JDK 21 进程/并发能力与 slf4j-api；不增加任何 VFS 保留路径或 `.git` 保护机制，完全依赖 Git 原生行为；不接入调度器（T35）。

## 2. 交付文件列表

- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitProcessRunner.kt`: 进程执行接口与结果模型（含 `cancelled`、`cleanupFailed`）。
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/JvmGitProcessRunner.kt`: ProcessBuilder、双流排空、单一截止时间、级联进程清理。
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitSecurity.kt`: `GitSecurityError` 受控错误码与 `sanitizeUrl`。
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/SyncResult.kt`: `SyncStage` 与结果类型（含 `Failed.cancelled`）。
- `vfs/git-backup/src/main/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitRepository.kt`: 仓库预检、暂存、按需 commit、补偿 push、poisoned 阻断。
- `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitSecurityTest.kt`: 脱敏单元测试（3 例）。
- `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitRepositoryTest.kt`: 仓库集成测试（19 例）。
- `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupLogCaptureTest.kt`: 内存 Logger 日志捕获测试（1 例）。
- `vfs/git-backup/src/test/kotlin/com/github/noahshen/alcyone/context/vfs/gitbackup/GitBackupTest.kt`: T33 配置/Cron/事件筛选测试（12 例）。
- `docs/tasks/e1-t34/DELIVERY_T34.md`（本文档）

## 3. 测试与验证记录

- 本地 Git 版本: `git version 2.50.1 (Apple Git-155)`
- JDK: `21.0.12.1+1`（项目内工具链）

### 3.1 命令与退出码

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `./scripts/dev gradle spotlessApply` | 0 | BUILD SUCCESSFUL |
| `./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test` | 0 | BUILD SUCCESSFUL |
| `./scripts/check` | 0 | BUILD SUCCESSFUL in 1m 10s, 75 actionable tasks |

### 3.2 计数（已归档实际统计命令）

归档命令（在仓库根执行）：

```bash
# 常规 XML 用例总数（仅 src/test 的常规测试，不含 WebDAV 的 webdavTest）
find . -path '*/build/test-results/test/*.xml' -print0 \
  | xargs -0 grep -ho 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc

# 常规源码 @Test 行数（排除 webdavTest；用 @Test\b 排除 @TestMethodOrder 等前缀误匹配）
find . -path '*/src/test/*' -name '*.kt' -not -path '*/webdavTest/*' -print0 \
  | xargs -0 grep -hoE '@Test\b' | wc -l
```

实测结果：

- 常规源码 `@Test` 行数: **799**
- 常规 XML 用例总数: **799**（差额为零）
- 既有模块常规回归: **752**；git-backup: **47**
- WebDAV 源码 `@Test`: 43（单独 `webdavTest` 任务，不计入常规）
- 模块 XML 报告（`vfs/git-backup/build/test-results/test/`）:
  - `TEST-...GitBackupTest.xml`: tests=12, failures=0, errors=0
  - `TEST-...GitRepositoryTest.xml`: tests=23, failures=0, errors=0
  - `TEST-...GitSecurityTest.xml`: tests=3, failures=0, errors=0
  - `TEST-...GitBackupLogCaptureTest.xml`: tests=1, failures=0, errors=0
  - `TEST-...JvmGitProcessRunnerTest.xml`: tests=8, failures=0, errors=0

### 3.3 R1～R5 反例/正向测试清单

| 修复项 | 用例 | 断言要点 |
| --- | --- | --- |
| R1 | `sanitizeUrl masks password and never echoes it` | `https://fixture:PASSWORD@host/repo.git` → `https://fixture:***@host/repo.git`，不含密码 |
| R1 | `sanitizeUrl masks password embedded in a larger output` | 长文本中密码被遮蔽，其余内容保留 |
| R1 | `failure logs use controlled codes and never leak credentials` | 捕获真实日志：含受控码 `PUSH_PUSH_FAILED`，绝不含密码或原始 URL |
| R2 | `initial empty repository with no files skips cleanly` | 真空仓库 → `SkippedNoContent`，远端无分支 |
| R2 | `pre-commit hook failure on first commit is a commit failure not skipped` | hook exit 1 → `Failed(COMMIT, COMMIT_COMMIT_FAILED)`，非 `SkippedNoContent` |
| R2 | `file modifications additions deletions gitignore and no-change behavior` | 增删改、ignore、无改动不产生空提交 |
| R2 | `status failure is a commit head probe failure not skipped` | 注入 status exit=128 且 stdout 空 → `Failed(COMMIT, COMMIT_HEAD_PROBE_FAILED)`，非 `SkippedNoContent` |
| R2 | `head probe failure is a commit head probe failure` | 注入 rev-parse HEAD exit=128 → `Failed(COMMIT, COMMIT_HEAD_PROBE_FAILED)` |
| R2 | `first commit and push to bare remote` | 正常 unborn 分支仍能完成首次 commit/push |
| R3 | `cancellation exception during stage aborts with cancelled semantics` | 注入 `CancellationException` → `Failed(STAGE, STAGE_ADD_CANCELLED, cancelled=true)` |
| R3 | `interrupt cancels running command and keeps cancellation semantics` | 真实 `interrupt()` → `cancelled=true`、`timedOut=false`、`cleanupFailed=false`、中断位恢复 |
| R3 | `interrupt during output wait after parent exit is not lost` | 父进程先退出、读线程仍等待时中断 → `cancelled=true`、`outputTruncated=true`、中断位恢复、耗时远小于原截止时间 |
| R3 | `parent exit with held pipe marks output incomplete` | 父退出而子持有管道 → `outputTruncated=true`；`cleanupFailed` 与读取线程是否确认一致 |
| R3 | `reader threads exit after successful command` | 命令结束后无 `git-*-drain` 线程存活 |
| R3 | `truncated protocol output is rejected` | 注入 `outputTruncated` → `Failed(STAGE, OUTPUT_TRUNCATED)` |
| R3 | `truncated output is flagged` | 限额 8、输出 20 → `outputTruncated=true` |
| R3 | `large stdout and stderr are both drained` | stdout 与 stderr 各 20 万行 → 不挂起、`outputTruncated=true` |
| R3 | `timeout kills process and descendants with verified pids` | 父/子真实 PID 均退出 |
| R3 | `command timeout cleans up process safely` | `sleep 10` 超时触发、`cleanupFailed=false` |
| R3 | `cleanup failure poisons repository and blocks subsequent syncs` | 首次 `PROCESS_CLEANUP_FAILED`，后续不再发起命令（调用计数不变） |
| R3 | `large output draining does not deadlock process` | ~260KB 输出排空不挂起 |
| R4 | `validate multiple push URLs fails` | 多 pushurl → `VALIDATE_MULTIPLE_PUSH_URLS` |
| R4 | `validate multiple fetch URLs fails` | 多 url → `VALIDATE_MULTIPLE_PUSH_URLS` |
| R4 | `validate linked worktree as source fails but main worktree is accepted` | worktree 拒绝 `VALIDATE_LINKED_WORKTREE`，主工作区不误拒 |
| R4 | `validate nested repository fails` | 嵌套 `.git` → `VALIDATE_NESTED_REPO` |
| R4 | `validate staged gitlink without gitmodules fails` | 仅暂存 mode 160000 gitlink、无 `.gitmodules` → `VALIDATE_NESTED_REPO` |
| A01 | `validate non git directory fails` / `...sub-directory...` / `...bare...` / `...branch mismatch...` / `...missing user name...` / `...in-progress merge or rebase...` | 各预检失败码正确 |
| A02 | `first commit and push to bare remote` | 首个 commit 与 push，远端 SHA 一致 |
| A03 | `commit succeeds but push fails retains local commit and compensates on next run` | push 失败保留本地 commit，下一轮无新改动补推成功 |
