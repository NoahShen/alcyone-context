# CT29 B2 Main 与关闭失败接缝待裁定

## 本次修改（2026-11-11，方案 B 批准，Worker 恢复）

- Architect批准方案B，见 [正式裁定](B2_Main与关闭失败接缝裁定.md)：顶层main原样移StandaloneMain.kt，Command可internal注入factory/termination。
- public生产run/hook/输出/解析不变，无public全局mutable fake；真实installDist SIGTERM/SIGINT/Ready/权限/drain走public入口，仅close失败4走受控子进程internal+Managed.createForTesting。
- 不改Managed/第二套Runtime；分别标生产信号和受控关闭失败日志。B2未DONE，B3/CT30+不放行，无Git写。

## 历史修改（2026-11-11，Worker 暂停，Lead 提交最小方案）

- 预检查未改build/生产/测试，未执行installDist，未宣称signal通过。
- 当前真正顶层 `main` 在 `StandaloneCommand.kt`，JVM类为 `StandaloneCommandKt`；`StandaloneMain.kt` 只有空object，委派单指定 `StandaloneMainKt` 会ClassNotFound。
- run正常termination后的closeAsync失败会映射4；catch内二次close失败被吞但原失败仍4。真实受控close失败子进程需要注入Managed factory/termination，现有public run硬编码不能构造受控close失败，且不能改Managed。

## Lead 推荐最小方案 B

1. 将现有顶层 `main(args)` 原样移至 `StandaloneMain.kt`，删除Command旧顶层main；行为不变，Application使用既定 `StandaloneMainKt`。空object删除。Command SHA因此定点变化，需Architect批准。
2. Command新增internal执行重载/参数，仅供同模块测试注入 `ManagedAlcyoneHttpServer` factory与termination Deferred；public `run` 保持生产factory、真实shutdown hook和固定退出码。受控close失败子进程通过internal接缝调用 `Managed.createForTesting` + fake Context close failure，不另写第二套命令解析/run逻辑，不改Managed。
3. 真实SIGTERM/SIGINT测试必须调用生产installDist入口和public run，不走注入；仅close失败用受控子进程。所有秘密/退出码规则不变。

备选A是mainClass改为 `StandaloneCommandKt` 并加同类internal接缝，改动较少但保留空StandaloneMain且与既定入口命名不一致。Lead优先B。

请Architect裁定后Worker继续；B2未DONE，B3/CT30+不放行，无Git写。
