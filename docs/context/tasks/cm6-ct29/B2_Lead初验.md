# CT29 B2 Lead 独立初验

## 本次修改（2026-11-11，R1/R2 关闭，独立初验通过，提交 Architect）

- Managed grace已由Architect明确追认；build仅新增 `test dependsOn(installDist)`，当前SHA `72cb1f571c9cd5b0214e6e811393aa488dbb1bb833730b9de575a2e93b16529f`。Command `97b4b323…90364`、Main `4c4cd44a…288717`、Managed `9679ba8…e5616`稳定；Offline `a180dba5…`、SqliteBackup `23a79675…`、Schema `ec727a53…`不变。
- Lead clean后普通Server test `/tmp/ct29-b2-lead-r2-clean-server.log` exit0、31/31、1m13s，自动startScripts/installDist/test，**106/106**、0bad，未手工预建产物。
- Lead完整 `./scripts/check` `/tmp/ct29-b2-lead-r2-check.log` exit0、**137/137**、3m26s；14模块 **2259=2259**、0 failure/error/skipped，明细 `/tmp/ct29-b2-lead-r2-xml.txt`。
- 此前独立真实installDist15/15、Server/Runtime929、四模块1240及Spotless证据保持。R1范围已追认、R2任务依赖关闭。提交Architect裁定B2；B3/CT30～CT32未启动，无Git写。

## 历史修改（2026-11-11，R2：普通全仓缺 installDist 任务依赖）

- Managed grace已获Architect明确追认；Lead四模块fresh exit0、32/32、1m，Spotless通过。
- 但普通 `./scripts/check` `/tmp/ct29-b2-lead-check.log` exit1、106 tests/7 failed：StandaloneProcess六例均 `run :context:server:installDist first`，MainTest生成脚本NoSuchFile。定向fresh曾显式installDist，不能覆盖普通check失败。
- 已退回Worker仅修 `tasks.test dependsOn(installDist)`，不得skip或手工预跑产物掩盖；clean后普通Server test与全仓check复验。B2未DONE，B3/CT30+不放行，无Git写。

## 历史修改（2026-11-11，IN_REVIEW / 范围 R1，提交 Architect 裁定）

- Lead真实installDist/进程复跑 `/tmp/ct29-b2-lead-process.log` exit0、30/30、1m1s；StandaloneProcess6 + Main3 + Managed6=15/15。TERM/INT0、受控close失败4、4MiB drain、端口4固定RUN_FAILED、SQLite重开均实测，无143/stacktrace。
- Lead Server/Runtime fresh `/tmp/ct29-b2-lead-server-runtime.log` exit0、31/31、1m47s，XML **106+823=929**、0bad。Application生成脚本SHA `0d022320…69807ee` 使用exec JAVACMD；Standalone生产SHA Command `97b4b323…90364`、Main `4c4cd44a…288717`、build `0ec5775d…c634fd`。
- **范围R1**：Managed当前SHA `9679ba8136ae2160e9b6e66e1829fadf428f835bf5a943cf49dc877ccebe5616`，相对B1保护 `0bad2059…44f0` 已变，不在B2 Main/Signal两次裁定允许集合。差异仅closeOnce CIO stop grace由0改1000ms，registry awaitDrained仍先于CIO stop，1秒不是drain成功信号，真实4MiB响应验证有效。但Worker未先暂停获批，Lead不能追认；请Architect明确追认该最小修正或退回原SHA并另定drain方案。
- B2不标DONE，B3/CT30～CT32不放行，无Git写。

## 1. 独立技术检查

- shutdown hook complete termination后对同一Managed closeAsync，main await+close共享Managed closeJob；hook真实结果halt0/4，close失败TERM/INT4，无143。
- 仅StandaloneMain安装default handler，普通后台异常静默，fatal委托原handler/默认stack；Library run不改全局。真实端口stderr恰RUN_FAILED，无BindException/secret。
- installDist真实含空格init/upgrade/run；Ready/Read/Admin401/403/200；收到1KiB后发signal，完整读取4MiB；独立重开Context/VFS SQLite。
- close失败受控子进程delegate真实Factory/VFS读取，仅close抛受控异常；真实信号测试public入口，注入未冒充生产。
- Managed 1秒仅CIO网络flush/idle keep-alive收口，不是逻辑drain完成信号；但修改范围须Architect裁定。

## 2. 后续

若Architect追认Managed，Lead继续四模块/全仓check14模块后B2初验通过；若要求回退，仅Worker按裁定定点修，真实进程重跑。当前不启动B3，无Git写。
