# CT28 B2c Lead 初验

## 本次修改（2026-11-11，R1 关闭，独立初验通过，提交 Architect）

- R1仅改 `ManagedAlcyoneHttpServer.kt`/`ManagedAlcyoneHttpServerTest.kt`，生产SHA `0bad205977f064b30d75d1a9787439a2d17fe014a54db3cb1cbdc85d2e8544f0`，测试SHA `d5bad9ccfec0792af70586ab5a0e4a1a43a6af6115c1d66756e8de15b4483a55`。
- Registry删除主动请求取消，close顺序为封准入→awaitDrained正常完成→stopSuspend CIO→closeAsync Runtime；真实Dreaming HTTP200且run自然完成、流自然结束，Runtime不提前关闭。close等待start真实结果，失败重放原异常且清理一次，成功/失败并发close共享Deferred，caller取消不传播。
- 真实占用端口在独立JVM触发BindException，已start Context只close一次，重复close重放失败；受控启动/监听失败逆序清理与suppressed保留。
- Lead Server fresh `/tmp/ct28-b2c-lead-r1-server.log` exit0、27/27、24s，XML **87=87**、0bad；四模块 `/tmp/ct28-b2c-lead-r1-four.log` exit0、32/32、1m3s，**45/180/186/823=1234**、0bad。
- Lead全仓 `/tmp/ct28-b2c-lead-r1-check.log` exit0、132/132、2m32s；14模块 **2234=2234**、0 failure/error/skipped，明细 `/tmp/ct28-b2c-lead-r1-xml.txt`。Spotless/compileClasspath `/tmp/ct28-b2c-lead-r1-boundary.log` exit0，VFS Runtime仅一行implementation；Server生产无VFS/JDBC import。
- Admin POST完整8例：空/{}稳定DTO、三类身份/未配置/严格正文/413、Dreaming各stage、Backup各code与PRE_CHECK、取消/deadline、真实LocalFS独立JDBC quick_check与committed raw；未引入WebDAV兜底。Backup分类SHA `dc737edb…fdf0a9`，构建SHA `00da758a…a120dc`。
- B2a四SHA `583d1ee1…/078d4172…/5396e96e…/b75ea0a0…`，Schema `ec727a53…aadd8ea3`不变。提交Architect最终复核；B2c/CT28未自标DONE，CT29未启动，无Git写。

## 历史修改（2026-11-11，IN_REVIEW / R1，退回 managed 定点修复）

- 首轮Lead独立Server fresh exit0、85=85；Admin Manual 8例与Backup/构建接缝通过，但managed主动取消在途请求、closeJob启动失败结果共享缺陷及缺真实bind用例，裁定R1。仅修Managed两文件，现以上述复验关闭。

## 1. 关键实现与断言

- 生产 `ManagedAlcyoneHttpServer.kt:46-60` start/close single-flight；`:69-93` 启动失败逆序清理；`:96-132` drain/CIO/Runtime顺序；`:182-213` 无取消的准入计数。
- 测试 `ManagedAlcyoneHttpServerTest.kt:43` 并发start/close；`:72` 启动失败单次清理；`:104` 真实占用端口独立JVM；`:134` 失败重放；`:149` Dreaming真实200/正常drain；`:181` 流自然结束；`:204` 真实Runtime/SQLite；`:229` YAML。
- `AdminManualHttpApiTest.kt:55` 成功DTO；`:93` 鉴权/body；`:140/165` 阶段错误；`:193` 取消；`:228` 真实LocalFS Backup。

## 2. 未验证

真实收费模型、真实WebDAV原子发布、长期跨进程与生产部署not-run。systemd/Caddy/独立部署冒烟归CT29，未启动。无Git写操作。
