# CT28 B2c 手动管理与 managed 生命周期委派单

## 本次修改（2026-11-11，B2b DONE 后唯一 Worker 派发）

- Architect 已裁定 **B2b APPROVED / DONE，放行 B2c**。唯一 Worker 原 `c2816850-92fa-4bca-986b-8535ffa369d3`；必须真实实现与测试，不得只文档验收。B2c/CT28未DONE，CT29/systemd/Caddy不启动，无Git写操作。
- 仅Server生产/测试。复用B2a公共SDK、B2b Auth/HTTP辅助/安装器；不改公共SDK、B2a Runtime算法、Core/Persistence、Schema、VFS、CT27或构建。若确需API/Runtime扩展立即暂停报最小接缝。
- Backup保持local-atomic only，不将WebDAV publishNew拒绝降级普通写入。

## 1. POST /v1/admin/dreaming 与 /v1/admin/backup

- ADMIN-only，先authenticate/allowOperation再读body/SDK；401/403/未配置404矩阵与status一致。仅空body或严格 `{}`；按maxSearchBodyBytes做Content-Length预检和实际计数，严格UTF-8/重复键/未知字段/类型/尾随垃圾拒绝400，+1为413。不得接收目标URI/路径。
- 调并等待真实 `runDreaming()` / `backupNow()`，不另建业务Job，不走直接VFS/DB。成功显式稳定snake_case DTO：Dreaming六字段；Backup uri/size_bytes/completed_at/quick_check(phase,ok)，无partial异常文本/秘密/临时路径。
- 使用requestTimeoutMillis的Server自有deadline：未提交时504 REQUEST_TIMEOUT并取消本次等待；外部CE与SDK自身TimeoutCancellationException原样传播；已提交响应不追加JSON。close在途时按 managed 契约等待真实SDK收尾。
- Dreaming：UNAVAILABLE503 DREAMING_UNAVAILABLE；ALREADY_RUNNING409；DreamingRunException按FIXED_RANGE/PAGE_READ/BUDGET/BATCH_PROCESSING映固定500 `DREAMING_<stage>`；其他lifecycle/persistence503，未知500 INTERNAL_ERROR。
- Backup：UNAVAILABLE503 BACKUP_UNAVAILABLE；ALREADY_RUNNING409；CLOSED/NOT_STARTED503 SERVICE_UNAVAILABLE；QUICK_CHECK_FAILED/PUBLISH_FAILED500具名code。可import Runtime `failureStage` 仅将其他受控BackupStageFailure映 SNAPSHOT/PRE_CHECK/POST_CHECK固定500；CE不映射。禁止输出cause/message，不兜底WebDAV。
- Fake精确成功/失败/并发/取消；真实Runtime/SQLite/LocalFS证明Backup快照可独立open/quick_check/内容核对。未配置能力503。真实收费模型not-run。

## 2. managed Server

新增 `ManagedAlcyoneHttpServer.kt`，提供直接配置与YAML入口；YAML分别经现有 `ContextConfigLoader.load` / `ServerConfigLoader.load`，不绕过校验。构造handle不取资源。

### 启动

- 重复/并发start共享一次真实结果：`AlcyoneContextFactory.create → context.start → CIO listen`，全成功才发布可用。失败逆序：若已listen先停HTTP，已start Runtime再closeAsync；主失败保留并附加清理失败，无DB/executor/client泄漏。
- 端口绑定失败、Context启动失败、安装部分失败有真实/受控证据；并发start只create/start一次。外部 `AlcyoneHttpServer.start(config,context)` 仍不start/close Context，回归不变。

### 请求注册与关闭

- managed全路由共享准入状态/在途计数/完成信号；不得用sleep充当drain证据。close原子封闭新请求，停止listener，等待Ktor handler/response writer及在途请求、Resource producer和手动Dreaming/Backup真实完成，最后才context.closeAsync。
- 重复/并发close等待并重放同一成功/失败。调用方取消只放弃等待，不取消共享清理；真实清理由NonCancellable cleanup owner完成。观察超时可具名失败，不得当drain成功继续释放Runtime。
- B1首块预取、已提交后abort、producer cancel/join/finally保持。caller-owned手动run Job须在Runtime close前完成契约收尾。事件顺序用确定性gate断言：封准入→在途/CIO完成→Runtime释放；DB释放最后。
- 至少真实CIO+SQLite覆盖在途流与手动run关闭清理、并发close、端口失败与部分启动；外部所有权零生命周期调用继续。

## 3. 精确写集合

生产：`AdminHttpApi.kt`、`AdminHttpDto.kt`、`HttpApiSupport.kt`、`ReadHttpApi.kt`（仅managed注册接缝）、`IngestionHttpApi.kt`（仅managed注册接缝）、`AlcyoneHttpServer.kt`（仅共享安装接缝），新增 `ManagedAlcyoneHttpServer.kt`。默认不改 `ServerAuth.kt`/Config/Loader；确需仅managed入口解析接缝时先报。

测试：既有Admin/Health/Read/Resource/Ingestion/Auth/Skeleton/Fixture辅助；新增 `ManagedAlcyoneHttpServerTest.kt`，可新增Server测试专用受控Context/启动接缝，不得改生产公共SDK。不得为通过改B1/B2a/B2b断言语义。

B2b终态SHA `/tmp/ct28-b2b/final.sha256`；B2a四SHA与Schema按 `B2b_Lead初验.md`。不得修改其他模块。

## 4. 验收与交付

- 三Admin路由全部鉴权矩阵；POST空/{}成功、其他正文/413且未调用SDK；未配置/并发409/各阶段/取消/超时。真实LocalFS Backup独立SQLite打开、quick_check与数据核对。
- managed并发start、重复/并发close、监听/启动失败逆序清理、在途请求/流/手动run真实drain与DB最后释放、外部所有权回归。响应/日志无秘密。
- `./scripts/dev gradle spotlessApply`；Server fresh全套；api/core/persistence/runtime四模块fresh；全量 `./scripts/check`、14模块源码@Test=XML且0失败/错误/跳过；Schema/B2a保护SHA。
- 交付精确文件/SHA/路由与断言行号、日志/退出码。B2c未DONE，CT28未DONE，CT29不启动，无Git写。
