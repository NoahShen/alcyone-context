# CT28 B2b Server Read/Admin/Health 委派单

## 本次修改（2026-11-11，B2a DONE 后唯一 Worker 派发）

- Architect 已裁定 **B2a APPROVED / DONE，放行 B2b**。唯一 Worker 为原 `c2816850-92fa-4bca-986b-8535ffa369d3`；Lead 不代写生产，B2b 未 DONE，B2c/CT29 不放行，无 Git 写操作。
- 本批仅 `context/server`：沿用 B1 `ServerAuthRegistry`、identity/固定类别授权、严格 body/UTF-8/错误辅助与路由安装器，收敛既有 Read，新增 Admin status 与 Live/Ready。
- 不修改 B2a Runtime、公共 Context API、Core/Persistence、Schema、VFS、构建生产依赖或 B1 已验证业务语义。Backup 按 Architect 最新明确指示不加入 Server；Dreaming 手动 HTTP 也不在本批扩大。Turn/session list/read、Recent/list、Resource 批量/open 新公共能力不属于 CT28 已批准 API，不得虚构或绕过 Runtime 查库。

## 1. 路由与确定性鉴权

- `GET /v1/context/bootstrap`、`POST /v1/memories/search`、`GET /v1/memories/{id}`、`GET /v1/raw-memories/{id}`、`GET /v1/resources/meta?uri=...`、`GET /v1/resources/content?uri=...` 保持 B1 唯一 SDK 委派；全部经同一 authenticate + READ allowOperation 语义，缺/未知/畸形401，已识别错类403，认证先于 body/SDK。
- `GET /v1/admin/status` 仅 ADMIN，调用 `getAdminStatus()`，显式稳定 snake_case 完整安全投影：lifecycle、四 worker、pending、dreaming、mapStatus、llm、backup 的 B2a DTO 事实原样映射；null/空集合不混淆，无 path、Throwable/message、Key/model/baseUrl/后端信息。Admin 不可访问 Read/Ingestion，Read/Ingestion 不可访问 Admin；未配置 Admin Key 不可访问。
- `GET /health/live` 无 Key、固定200短体，不调用 Context。`GET /health/ready` 无 Key，只调用 `checkReady()`，true200，false/异常/Server ready deadline503固定 `NOT_READY`；不调用 getAdminStatus/getStatus，不暴露依赖细节。外部 CE 原样传播，SDK 自身 TimeoutCancellationException 不误映504。
- 既有 Ingestion 路由身份绑定与兼容不变；每类身份只准本类操作，未知默认deny。Read 现有错误映射保留：400/401/403/404/413/503及 Resource 流中断，不重写流水线。

## 2. Ready 配置与错误边界

- `ServerLimits` 增加 `readyTimeoutMillis: Long = 2000`，正数；`ServerConfigLoader` 严格解析、非法/未展开ENV构造前拒绝，`toString`/错误不回显秘密。仅 ready 使用此 deadline，不与 Read 的 requestTimeout 混淆。
- `AlcyoneHttpServer.start(config, context)` 外部 Runtime 所有权保持：只监听/关闭 CIO，不启动/关闭 Context；Admin/Health 与 Ingestion/Read 一起注册。Admin/Health 不应因仅 Ingestion 配置而消失；health始终可访问。
- 固定错误映射：生命周期/Persistence unavailable/Admin DB失败/Ready失败503；未识别500 INTERNAL_ERROR；不得返回原始异常文本。Ready timeout只取消本次等待，不声称强杀JDBC。

## 3. 精确写集合

生产：

1. `HttpApiSupport.kt`：共享 deadline/响应或鉴权辅助的最小扩展。
2. `ServerAuth.kt`：仅在统一 allowOperation 确需时定点扩展。
3. `ReadHttpApi.kt`：统一鉴权/共享错误辅助，不改 SDK 委派与流语义。
4. `ReadHttpDto.kt`：仅确需的安全 DTO 扩展。
5. `AlcyoneHttpServer.kt`：注册 Admin/Health。
6. `IngestionHttpApi.kt`：仅统一共享辅助且保持兼容。
7. `ServerConfig.kt`、`ServerConfigLoader.kt`：readyTimeoutMillis。
8. 新增 `AdminHttpApi.kt`、`AdminHttpDto.kt`（Admin status + Health 可同文件/另建 HealthHttpApi.kt）。

测试：既有 `ReadHttpApiTest.kt`、`ReadHttpResourceTest.kt`、`ReadHttpSerializationTest.kt`、`ReadRuntimeFixture.kt`、`ServerAuthTest.kt`、`ServerFixture.kt`、`ServerSkeletonTest.kt`、`ServerConfigLoaderTest.kt`；新增 `AdminHttpApiTest.kt`、`HealthHttpApiTest.kt`。不得修改其他模块或测试来制造通过。

基线 SHA 保存 `/tmp/ct28-b2b/baseline.sha256`；B2a 终态 SHA 以 Lead初验顶部为准，Schema `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`。

## 4. 必须验收

- 真实 Runtime/SQLite/Local FS + 内存Ktor：Admin完整投影200，Read与Resource现有200不回归；fake Context精确覆盖identity/scope、Admin异常、Ready分支。未配置Key、未知401、错类403且0 SDK调用。
- Ready真200；false、真实DB失败、关闭、ready timeout均503；Live无Runtime200；pending非空/Map非CURRENT/LLM历史失败时ready不因这些事实503。不得用sleep作为唯一完成证据。
- JSON稳定、字段完整且无敏感信息；Admin DB失败503不伪零；Ready不调用其他Context方法。既有Read严格JSON/413/404/FAILED/UNAVAILABLE/流测试保持。
- 运行 `./scripts/dev gradle spotlessApply`；Server全套 `--no-build-cache --rerun-tasks`；指定四模块 api/core/persistence/runtime；全量 `./scripts/check`、14模块源码@Test=XML且0失败/错误/跳过。交付日志、退出码、SHA、路由/断言行号。
- 本批不实现 managed、不接 Backup/Dreaming HTTP，不宣称CT28完成；真实收费模型、WebDAV、生产部署not-run。
