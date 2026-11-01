
## 7. 接口裁定（A）修正后 Lead 独立复核（2026-10-29）

- **接口**：`AlcyoneContext` 迁至 `context/api` 包，`AutoCloseable` + `suspend start()` + `override fun close()` + `suspend closeAsync()` + `suspend getStatus()`；runtime 重复定义已删除；api 仅依赖 coroutines，无 Runtime/JDBC/YAML 依赖；未追加 CT07 桩。
- **共享清理**：唯一 `CloseState`（`CompletableDeferred` + `CountDownLatch`）；sync/async 发起者执行同一套 `vfs.close()` + `executor.closeAsync()`；等待均在锁外。同步入口在独立清理线程执行 `runBlocking`，调用线程不可中断等待、中断后继续等真实结果并恢复中断标志；挂起入口在 `NonCancellable + Dispatchers.IO` 执行，不占用调用者 dispatcher。
- **失败传播修复**：close 获胜分支的释放失败写入 `terminalFailure` 并由 start/close 共享；`shutdown` 不再吞 `startAttempt.await`；区分正常竞态（start 拒绝、close 成功）与真实清理失败（close 及后续等待者同一失败实例、状态 CLOSED）。
- **测试钩子**：internal `ReleaseProbe`（VFS/EXECUTOR），生产空实现。
- **Lead 独立实跑**：`:context:api:compileKotlin :context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` → exit 0；XML **47 例 0 失败/错误/跳过**；源码 `@Test`=47=XML 47，差额 0（新增 `AlcyoneContextCloseSharingTest` 5 例）。日志 `.local/validation/cm1/lead/ct06/b2-fix-check.log`。
- **新增断言**：sync/async 混合 close 只清理一次并共享结果；释放失败由 close 与所有后续 close/closeAsync 共享同一实例、状态 CLOSED、资源仍释放；start/close 竞态释放失败由两者共享；同步线程 interrupt 不中止清理、最终成功且恢复中断标志、连接已关；单线程 dispatcher 上 closeAsync 与在途协程无死锁；create 前后文件系统快照一致（零文件/零目录/零锁）。
- **SHA**：api `AlcyoneContext.kt`=`4ba048c4…`；`AlcyoneContextFactory.kt`=`1e0a3b14…`；`AlcyoneContextImpl.kt`=`902899af…`；VFS/CT04/Persistence 未改。
- **结论**：B2 裁定修正完成，Lead 初验通过，提交当前 Architect 独立复核；CT06 未标 DONE，CT07/CM2 未启动。

## 8. §5 R1（配置集合所有权）与测试判据加强后 Lead 独立复核（2026-10-29）

- **R1 修复**：`Factory.build` 在**校验前**取自有快照 `vfsConfig.copy(namespaces = namespaces.toSet(), mounts = mounts.toList())`，对快照校验（`validateVfs`、同文件、LocalFs 暴露），并把同一份快照交给实现；调用者事后修改原集合不影响 start。未改 VFS/CT04，未新增公共接口（仅 internal `StartGate`/`startWaiterEnteredForTesting`/`closeWaiterEnteredForTesting`/`vfsForTesting`）。
- **Lead 独立实跑**：`:context:api:compileKotlin :context:runtime:test :context:runtime:spotlessCheck --rerun-tasks` → exit 0；XML **49 例 0 失败/错误/跳过**；源码 `@Test`=49=XML 49，差额 0（新增 `AlcyoneContextConfigOwnershipTest` 2 例）。日志 `.local/validation/cm1/lead/ct06/b2-fix2-check.log`。
- **Lead 重跑两条探针（JDK21，非引用 Worker 日志）**：
  - 修后 `MutableConfigProbeFix`：`START_ACCEPTED=true`、`ACTUAL_VFS_LIST=[alcyone://memory/safe-marker.txt]`、`SAFE_MARKER_VISIBLE=true`、`PRIVATE_MARKER_VISIBLE=false`、`CONTEXT_DB_VISIBLE=false`、`MUTATED_MOUNT_LEAK=false`。
  - 原 `MutableConfigProbe`（保留为历史）：`START_ACCEPTED_MUTATED_MOUNT=true`（硬编码，仅表示 start 返回）、`ACTUAL_VFS_LIST=[]`（被改的 private 挂载未被使用）。
  - 判据按 Architect 纠正执行：不以探针字符串转绿，改用真实 `VFS.list` 行为断言（safe marker 可见、private marker 与 context.db 系列不可见）。
- **测试判据加强（已核实）**：close 等待者先等 `closeWaiterEnteredForTesting() > 0` 再 `cancelAndJoin`；并发 start 成功例先等 `startWaiterEnteredForTesting() > 0` 再放行探针（probe 只调一次、两者成功）；失败例用 internal `StartGate` 阻塞首个 start，确认第二个已进入 STARTING 后放行失败，断言两者同一失败实例、状态 CLOSED、无 VFS 残留。
- **SHA**：`AlcyoneContextFactory.kt`=`0e87697abd793bb3…`、`AlcyoneContextImpl.kt`=`18a9e64dfa4d5921…`；api 接口与 B1 文件未变；VFS/CT04/Persistence 未改。
- **结论**：B2-R1 关闭，B2 初验通过，提交当前 Architect 独立复核；CT06 未标 DONE，CT07/CM2 未启动。
