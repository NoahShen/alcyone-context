# CT28 B2b Lead 初验

## 本次修改（2026-11-11，独立初验通过，提交 Architect）

- 生产仅 Server：新增 `AdminHttpApi.kt`/`AdminHttpDto.kt`，定点修改 `AlcyoneHttpServer.kt`、`HttpApiSupport.kt`、`ReadHttpApi.kt`、`ServerConfig.kt`、`ServerConfigLoader.kt`；测试新增 Admin/Health 并扩 Loader/Skeleton。未改 B2a Runtime/API/Core/Persistence、Schema、VFS或构建。
- Admin仅ADMIN，完整稳定 snake_case 安全投影 lifecycle/四worker/pending/dreaming/map_status/llm/backup，显式null与空数组区分；未知/缺失401、错类403、未配置路由404，失败固定503/500，无异常文本/path/Key。Backup仅在既有status中投影，无Backup/Dreaming HTTP端点。
- Live固定200不触Context；Ready仅checkReady，true200、false/DB失败/关闭/自有deadline503，SDK/外部CE传播；readyTimeoutMillis独立严格正Long。无client纯Admin/Health可用，有client保持Ingestion限额；外部Context不start/close。Read六路由统一requireOperation且原流/错误语义未变。
- Lead Server fresh `/tmp/ct28-b2b/lead-server.log` exit0、27/27、23s、XML **73=73**；指定四模块 `/tmp/ct28-b2b/lead-four.log` exit0、32/32、59s，**45/180/186/823=1234**，零bad；Server Spotless exit0。
- Lead全仓 `/tmp/ct28-b2b/lead-check.log` exit0、132/132、2m22s；14模块 **2220=2220**，0 failure/error/skipped，明细 `/tmp/ct28-b2b/lead-xml.txt`。`sha256sum -c /tmp/ct28-b2b/final.sha256`全OK，Lead清单 `/tmp/ct28-b2b/lead-final.sha256`。
- B2a保护SHA：AlcyoneContext `583d1ee1…b7754`、ContextStatus `078d4172…d7`、Factory `5396e96e…8271`、Impl `b75ea0a0…2607`；Schema `ec727a53…aadd8ea3`不变。提交Architect独立复核；B2b未自标DONE，B2c/CT29未启动，无Git写操作。

## 1. 关键断言位置

- `AdminHttpApiTest.kt:40` 完整字段、snake_case、null/empty与敏感标记；`:77` 三类身份/0 SDK/固定失败；`:104` 未配置Admin路由；`:114` 真实SQLite与DROP organization_pending后503不伪零。
- `HealthHttpApiTest.kt:29` live零调用、ready仅checkReady及false/异常；`:56` SDK TCE传播；`:76` 独立deadline；`:86` 真实SQLite drop checkpoint/关闭503；`:107` 不从Admin/其他依赖推断Ready。
- `ServerConfigLoaderTest.kt` readyTimeoutMillis合法/默认/非法；`ServerSkeletonTest.kt:33` 真实CIO无Key health与外部Context零生命周期调用。
- 生产路由 `AdminHttpApi.kt:33/49/52`；统一授权 `HttpApiSupport.kt:248`；安装器 `AlcyoneHttpServer.kt:35-43`；Read六处 `ReadHttpApi.kt:56/64/73/87/101/110`。

## 2. 未验证与边界

本批未实现managed启动/关闭、在途drain或手动Dreaming/Backup HTTP，未宣称CT28 DONE。真实收费模型、WebDAV原子发布、长期跨进程与生产部署not-run。A05/A07完整验收需按后续批准范围继续，不因本批全绿省略。
