# CT29 B2 Main 与关闭失败接缝裁定

## 本次修改（2026-11-11，批准方案 B）

- 核实真正顶层 main 当前在 StandaloneCommand.kt，JVM 类为 StandaloneCommandKt；StandaloneMain.kt 只有空 object。批准将顶层 `main(args)` 原样移至 StandaloneMain.kt 并删除空 object，Application mainClass 使用 `StandaloneMainKt`。旧 Command SHA `07b3ac5d…63b78`、Main SHA `a7d506f5…461`。
- Command 可新增 internal 执行重载/参数，注入 Managed factory 与 termination Deferred；public `run(args,stdout,stderr)` 签名、生产 factory、真实 shutdown hook、固定输出/退出码和命令解析不变。不得使用可被 public 入口开启的全局 mutable fake factory。
- 真实 installDist SIGTERM/SIGINT/Ready/权限/drain 必须走 public main/run，禁止注入；只有 closeAsync 失败退出 4 可由同模块受控子进程使用 internal 接缝与 Managed.createForTesting。不得另写第二套命令解析/run逻辑、不改 Managed、不建第二套 Runtime。
- B2 其余范围不变，B3/CT30～CT32 不放行，无 Git 写操作。交付分别标识生产信号进程与受控关闭失败进程证据。
