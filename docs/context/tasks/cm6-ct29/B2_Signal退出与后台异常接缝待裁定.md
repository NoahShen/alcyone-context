# CT29 B2 Signal 退出与后台异常接缝待裁定

## 本次修改（2026-11-11，两项修复批准，Worker 恢复）

- Architect批准，见 [正式裁定](B2_Signal退出与后台异常裁定.md)：真实hook与main共享同一Managed closeJob，等真实close后halt0/4，不得143；TERM/INT和close失败真实复验。
- 仅StandaloneMain安装default handler，普通后台Throwable静默，fatal委托原handler/默认行为；Library run不改全局。真实端口4且stderr恰RUN_FAILED。
- 仅StandaloneCommand/Main及进程/必要受控探针测试可改；Managed/B1 Offline/Persistence/Schema不变。B2未DONE，B3/CT30+不放行，无Git写。

## 历史修改（2026-11-11，真实 installDist 暴露生产阻断，暂停待裁定）

- 真实进程已验证含空格init/upgrade、配置secret不泄漏、ready/live、read/admin 401/403契约；占用端口main返回4/RUN_FAILED，但存在以下阻断，未接受143/stacktrace。
- 未改Managed，无Git写；B2未DONE，B3/CT30+不放行。

## 1. 信号退出码

受控close失败真实SIGTERM得到143而非4。JVM启动shutdown hooks后main退出不能覆盖信号最终code；当前hook只complete Deferred，main close/exitProcess无法决定最终退出。

Lead建议最小：真实hook在complete termination后，对**同一Managed实例** `runBlocking { closeAsync() }`，与main共享Managed closeJob幂等结果；按close真实结果 `Runtime.halt(0/4)`。main termination.await+close保留，正常非信号路径不变。internal测试用同一真实hook/factory，移除probe另装hook，不改Managed/第二套命令。

需Worker/Lead验证SIGTERM和SIGINT成功0、close失败4、无143、重复hook/main并发close单次清理与SQLite重开。

## 2. Ktor后台BindException堆栈

真实占用端口main最终4/RUN_FAILED，但CIO在DefaultDispatcher打印完整BindException stacktrace，违反固定安全stderr。

Lead建议只在 `StandaloneMain` JVM进程入口安装 `Thread.setDefaultUncaughtExceptionHandler`，对后台未捕获异常只输出固定安全标记或静默，主异常仍由Command统一RUN_FAILED/4；不在Library public run改全局handler。需确保不吞VM fatal Error（fatal仍dump/默认handler），不隐藏其他业务失败为成功；端口失败stderr无stacktrace/地址/配置secret。

请Architect批准上述B2定点生产修复或指定更窄方案。批准后仅StandaloneMain/Command及测试可改，Managed/B1 Offline/Schema不变。
