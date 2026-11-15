# CT29 B2 信号退出与后台异常裁定

## 本次修改（2026-11-11，批准两项真实进程定点修复）

- 真实 installDist 证明 shutdown hooks 启动后信号默认退出码可保持 143，main/exitProcess 不能覆盖；CIO 后台 BindException 也会越过 Command 打印堆栈。两项均为生产阻断，不接受仅单测通过。当前 Command SHA `71ed2eaa…ae56`、Main SHA `105712fb…183f`。
- 仅允许 StandaloneCommand/StandaloneMain 与 StandaloneProcess/必要受控探针测试定点修改；不改 Managed、B1 Offline、Persistence、Schema 或第二套 Runtime。B3/CT30～CT32 不放行，无 Git 写操作。

## 1. 信号与关闭结果

真实 shutdown hook 在 complete termination 后，对 run 使用的**同一 Managed 实例** `runBlocking { closeAsync() }`；主线程 termination.await+closeAsync 保留。二者只能共享 Managed closeJob/真实结果，不得二次取得或释放资源。

hook 必须在 JVM 退出前等待 close 的真实完成，按结果 `Runtime.getRuntime().halt(0)` 或 `halt(4)`；不得 halt 143，也不得把 close 失败当 0。内部测试注入 termination 不应安装另一个伪造关闭 hook；真实 TERM/INT 使用生产 installDist 入口，受控 close 失败使用同一真实 hook 与 internal factory。

真实验证：SIGTERM/SIGINT 成功均 0；close 失败均 4（至少 TERM，建议 INT）；无 143；并发 hook/main close 只清理一次；在途请求先 drain，SQLite 可重开。

## 2. 后台异常安全输出

仅在 StandaloneMain JVM 进程入口安装默认未捕获异常 handler；普通后台 Throwable（含 Ktor/CIO BindException）静默，不打印 stacktrace、地址、配置或秘密。主流程异常仍由 Command 输出固定 RUN_FAILED/4，不得把后台异常当成功或额外改变退出码。

VirtualMachineError、ThreadDeath、LinkageError 等 fatal Error 不吞；保存安装前 handler，fatal 委托原 handler（没有则保留 JVM 默认致命错误行为）。Library `StandaloneCommand.run` 不设置全局 handler，SDK 嵌入不受影响。

真实占用端口进程必须退出 4、stderr 恰为稳定 RUN_FAILED，无 BindException/stacktrace/秘密；Ready/Read/Admin/含空格路径等已通过项重跑。
