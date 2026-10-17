# T34 Lead 初验与技术审查记录（第三轮复核修复后重新初验）

## 本次修改（2026-10-16，Architect 收口证据口径订正）

- 直接订正清理保证与历史计数残留，不改生产代码和测试；最终结论以《T34 架构与技术复核》第10节为准。
- 有界等待指进程等待、读取线程 join 与清理等待。流 close 为同步 JDK 调用，后续 join 截止时间并不自动约束 close 本身；本机 JDK21/macOS 已验证持管道、超时与中断代表，不外推任意平台/异常内核的硬实时保证。
- cleanupFailed 仅表示本次可追踪进程或读取任务未确认退出；不能检测任意已脱离且不可追踪的子进程，也不能承诺这类进程必然触发 poisoned。


## 本次修改（2026-10-16，落实第三轮复核 R3/R5 定向收口重新初验）

- 针对 Architect 第三轮独立复核指出的 R3/R5 定向收口项（输出等待中断、读取资源确认、直接反例消除与文档统计精确性）完成独立实跑与代码复审；
- 先独立重现 Architect 探针反例，再验证修复后反例已完全消除；
- 状态保持 **IN_REVIEW**，提供完备的新旧计数、XML/源码精准对照（799 对 799，差额 0）及独立实跑原始日志；涉及全文。

## 1. 独立重现与反例消除证据

在工作区使用 JDK 21 工具链编译运行 Architect 提供的 `PipeProbe.java`（父进程 100ms 退出、子进程持有管道 3 秒）：

1. **未中断场景**:
   - 修复前: 300ms 截止时间，约 339ms 返回 `outputTruncated=true, cleanupFailed=false`；
   - 修复后: 约 344ms 返回 `outputTruncated=true, cleanupFailed=false`，正向行为稳定。
2. **200ms 中断输出等待场景**:
   - 修复前（反例）: 耗尽 2000ms 截止时间（约 2037ms），返回 `cancelled=false, cleanupFailed=false`，且调用线程中断位为 false（中断彻底丢失）；
   - 修复后（反例消除）: 约 733ms 返回，明确返回 `cancelled=true, outputTruncated=true, cleanupFailed=false`，且调用线程 `interrupted=true`（中断位成功保持并在结束后恢复，未继续盲等原 2 秒截止时间）。

## 2. 审查与验收结论

- **任务状态**: **IN_REVIEW**（第三轮复核定向收口初验通过，A01～A08 实质达成）
- **实现 Worker**: `c1d7c118-4bc9-41f5-93ab-e41b09259d73` (pi/Monk-olkey/monk-fast, high)
- **审查与边界收窄**:
  - **第 1 项（R3 输出等待中断）**: `markInterrupted(state)` 统一由 `joinThread`、`waitForExit`、`process.waitFor` 捕获 `InterruptedException` 时记录 `cancelled/interrupted`；中断后改用有限确认预算，不再等待到原截止时间；中断位在全部清理与确认结束后于 `finally` 恢复。
  - **第 2 项（R3 读取资源确认与边界澄清）**: `finally` 关闭三条流后再次有界确认读取线程退出，不能确认则标记 `cleanupFailed=true`；如实收窄保证范围：**仅承诺本进程、当前可追踪 descendants 及两个读取线程的确认**，不外推为检测任意不可追踪子进程；父进程已退后若存在未退出的持管道子进程，其主要通过读取线程未退出触发 `cleanupFailed` 进而 `poisoned` 阻断后续同步。
  - **第 3 项（R5 直接证据）**: 新增 `large stdout and stderr are both drained`（双流各 20 万行并发排空）；新增 `parent exit with held pipe marks output incomplete`、`reader threads exit after successful command`；测试在 `finally` 中主动清理创建的具体 PID 与 Worker 线程，无遗留进程。
  - **第 4 项（R5 统计命令与精准计数）**: 彻底废除旧的 840 混计口径，归档精确的排除脚本；全工程常规源码 `@Test` 为 **799**，常规 XML 为 **799**（既有 752 + git-backup 47，差额严格为零）。

## 3. Lead 独立实跑记录与证据归档

- **本地 Git 版本**: `git version 2.50.1 (Apple Git-155)`
- **Lead 独立实跑日志目录**: `.local/validation/t34-lead-r4/`
- **命令 1**: `./scripts/dev gradle spotlessApply`
  - 退出码: `0`
- **命令 2**: `./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test`
  - 退出码: `0`
  - 结果: **47 tests executed, 47 passed, 0 failed**
  - XML 报告 (`vfs/git-backup/build/test-results/test/`):
    - `GitBackupTest.xml`: 12 例
    - `GitRepositoryTest.xml`: 23 例
    - `GitSecurityTest.xml`: 3 例
    - `GitBackupLogCaptureTest.xml`: 1 例
    - `JvmGitProcessRunnerTest.xml`: 8 例
- **命令 3**: `./scripts/check > .local/validation/t34-lead-r4/check.log 2>&1`
  - 退出码: `0`
  - 结果: BUILD SUCCESSFUL in 1m 30s (75 actionable tasks executed)
  - 原始日志: `.local/validation/t34-lead-r4/check.log`

## 4. 全套源码与 XML 测试计数核对（归档统计脚本）

采用与 Architect 一致的模块目录遍历，严格排除 `webdav` 目录与注释行：

```python
# 统计命令脚本归档
python3 -c '
import os
modules = ["common", "vfs/api", "vfs/core", "vfs/storage", "vfs/persistence", "vfs/runtime", "vfs/git-backup", "integration-tests/vfs"]
total = 0
for mod in modules:
    test_dir = os.path.join(mod, "src", "test")
    if not os.path.exists(test_dir): continue
    for r, d, fs in os.walk(test_dir):
        if "/webdav" in r: continue
        for f in fs:
            if f.endswith(".kt") or f.endswith(".java"):
                with open(os.path.join(r, f), "r", encoding="utf-8") as fh:
                    for line in fh:
                        s = line.strip()
                        if s.startswith("@Test") and not s.startswith("@TestInstance") and not s.startswith("@TestMethodOrder") and not s.startswith("//"):
                            total += 1
print("Total regular @Test in modules:", total)
'
```

- **全工程常规源码 `@Test` 总数**: **799** 处（既有 752 + git-backup 47）
  - `common`: 10
  - `vfs/api`: 66
  - `vfs/core`: 305
  - `vfs/storage`: 154
  - `vfs/persistence`: 52
  - `vfs/runtime`: 61
  - `vfs/git-backup`: 47
  - `integration-tests/vfs` (常规): 104
- **全工程常规 XML 测试总数**: **799** 例（`Failures=0, Errors=0, Skipped=0`）
- **常规源码与 XML 差额**: **0**
