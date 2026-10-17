# T35 Lead 初验与技术审查记录（第三轮独立复核 R3/R4 定向收口后重新初验）

## 本次修改（2026-10-17，Architect证据归属订正）

- 正式取消结果测试覆盖取消前布置debounce、之后推进虚拟时间无新工作、拒手动、幂等close；取消异常测试覆盖拒手动、耗时日志及宿主存活。两者不直接断言所捕获Cron/debounce Job完成。
- 计时Job直接退出证据由Architect独立 `TerminationProbe.java` 补齐：取消结果与取消异常分别验证取消前计时Job活跃，取消后Cron/debounce/scheduler全部完成、待处理状态清空、后续请求不接纳且父Scope活跃。详见 `.local/validation/t35-architect-final/termination-probe.log`，不冒称正式单测或Lead实跑。


## 本次修改（2026-10-16，落实复核修复 R3/R4 重新初验）

- 针对 Architect 第三轮独立复核第 8 节指出的 R3/R4 证据缺口完成定向测试补充与独立初验；
- 先独立重现 Architect PendingProbe 反例，再核验修复后反例消失；
- 状态置为 IN_REVIEW，等待 Architect 独立复核；涉及全文。

## 1. 审查与验收结论

- **任务状态**: IN_REVIEW（初验通过，R3/R4 定向收口证据实质达成）
- **实现 Worker**: f458f775-42dd-4e4b-b905-a02048ad0330 (pi/Monk-olkey/monk-coding, high)
- **审查结论**:
  - **R3（合并信号不能遗留无唤醒 pending 状态）**: schedulerLoop 增加内层 while (!closing) 循环，每次信号唤醒后重新核对全部待执行状态（nextDueReason 重复调用），直到清空所有 pending 项；独立 PendingProbe 探针验证：修复前 THIRD_EVENT_STARTED=false CALLS=2 PENDING_EVENT=true，修复后 THIRD_EVENT_STARTED=true CALLS=3 PENDING_EVENT=false，反例已消除。新增 `pending event is woken and cleared after immediate precedence` 测试。
  - **R4（取消终止调度必须同时停止接纳和计时）**: 新增 terminateScheduling() 统一入口，取消/取消异常时设置 closing=true、取消 debounce/cron、清除 pending 状态并发送唤醒信号；requestSync() 增加 worker.terminated 检查，调度终止后抛 IllegalStateException 拒绝新请求；后续 close() 仍可靠完成且幂等。新增 `cancelled result stops timers and rejects requests` 与 `cancellation exception terminates scheduling and records duration` 两个测试，覆盖取消结果与取消异常两条路径；普通失败仍保持可再次触发。
  - **R1/R2 已关闭成果保留不重做**：订阅回调只持有轻量 EventConsumerHolder，关闭后断引用；close() 用 CompletableDeferred 共享清理完成信号，并发 close 等待同一次清理。

## 2. Lead 独立初验验证证据

- **本地 Git 版本**: git version 2.50.1 (Apple Git-155)
- **Lead 独立实跑日志目录**: .local/validation/t35-lead-r5/
- **命令 1**: ./scripts/dev gradle spotlessApply
  - 退出码: 0
- **命令 2**: ./scripts/dev gradle :vfs:git-backup:cleanTest :vfs:git-backup:test
  - 退出码: 0
  - 结果: 73 tests executed, 73 passed, 0 failed
  - XML 报告 (vfs/git-backup/build/test-results/test/):
    - GitBackupTest.xml: 12 例
    - GitRepositoryTest.xml: 23 例
    - GitSecurityTest.xml: 3 例
    - GitBackupLogCaptureTest.xml: 2 例
    - JvmGitProcessRunnerTest.xml: 8 例
    - GitBackupPluginTest.xml: 21 例
    - GitBackupWorkerTest.xml: 4 例
- **命令 3**: ./scripts/check > .local/validation/t35-lead-r5/check.log 2>&1
  - 退出码: 0
  - 结果: BUILD SUCCESSFUL (75 actionable tasks executed)
  - 原始日志: .local/validation/t35-lead-r5/check.log

## 3. 源码与 XML 测试计数核对

- **模块 XML 测试用例数**: 73 例（全部 passed，0 failures，0 errors，0 skipped）
- **全工程 XML 测试用例总数**: 825 例（原首版基线 752 + T33 12 例 + T34 35 例 + T35 26 例 = 825 例）
- **全工程 XML 状态统计**: Failures=0, Errors=0, Skipped=0
- **全工程常规源码 @Test 注解总数**: 825 处（与 XML 差额严格为零）
- 统计严格排除 WebDAV 43 与注释 1，不再出现 840 混计口径。

## 4. PendingProbe 反例独立重现与消除验证

- **探针路径**: .local/validation/t35-architect-r2/PendingProbe.java
- **修复前反例**: EVENT_DUE_BEFORE_RELEASE=true, THIRD_EVENT_STARTED=false CALLS=2 PENDING_EVENT=true
- **修复后结果**: EVENT_DUE_BEFORE_RELEASE=true, THIRD_EVENT_STARTED=true CALLS=3 PENDING_EVENT=false
- **结论**: 事件请求现在正确被唤醒并执行，pending 最终清空，R3 残留已消除。

## 5. 第 9 节测试证据核对

- **cancelled result stops timers and rejects requests**: STARTUP 闸门期间先建 Cron/debounce，放行取消结果后 requestSync 抛 IllegalStateException，推进受控时间 100s 无新增工作/残留，拒手动请求，捕获含 `durationMs=` 的取消终结日志，幂等 close 后自身 Scope 退出且宿主 Scope 活跃，失败 finally 可靠释放清理。
- **cancellation exception terminates scheduling and records duration**: 捕获含 `durationMs=` 的取消终结日志，幂等 close，自身退出且宿主 Scope 活跃，删除恒真断言 `assertTrue(true)`，失败时 finally 可靠释放清理。

## 6. 跨 T34 接线变更

- 允许的 T34 内部日志接线调整：GitRepository 不再写运行日志，日志责任由 GitBackupWorker 统一承担；既有 VFS/Runtime 与其余 5 生产模块 Diff 严格为 0。