# CT28 Architect 裁定

## 本次修改（2026-11-11，静态方案核对）

- 对照技术设计 §7.9、§9.4～9.6 与实际 Server/Runtime/API，批准 [CT28 任务书](CT28_任务书.md)，放行 Lead→Worker B1。
- 明确缓存状态与真实 Ready、SDK 状态与安全 HTTP DTO、外部 Runtime 所有权与 managed 入口的区别；列出 B2 接缝和技术确认要求。
- 用户已授权 CM6 全链；CT28/CT29 不设逐项人工授权门槛。这里只是方案裁定，不是实现验收或 CT28 DONE。

## 1. 已核对的事实

| 证据位置 | 事实与实施影响 |
| --- | --- |
| `context/server/.../IngestionHttpApi.kt` | 既有 Bearer、绑定runtime_id、有界读取、严格UTF-8、固定错误码；抽取共用基础，不重写入库业务 |
| `context/server/.../ServerConfig.kt`、`ServerConfigLoader.kt` | 目前只有Ingestion注册，Key→Map查找；需补auth分区、跨类冲突与安全比较，保留配置快照/ENV规则 |
| `context/server/.../AlcyoneHttpServer.kt` | CIO只持有Server，未拥有Runtime；不能悄悄改变旧入口资源所有权 |
| `IngestionHttpApiTest` 的 `http server wrapper listens and maps lifecycle to 503 then 201` | 正式回归明确先监听、后context.start；新managed入口补独立行为，旧测试不删改为新口径 |
| `context/api/.../AlcyoneContext.kt` | 所需Read/Dreaming/Backup业务已公开；HTTP直接委派，默认未实现能力保持UNAVAILABLE |
| `AlcyoneContextImpl.getStatus` / `StorageStatus` | initialized是缓存、status有绝对数据库路径，当前workers也并非设计中全部管理指标；HTTP不得盲目序列化或假称指标齐全 |
| `context/runtime/.../resource/ContextResourceAccess.kt` | 冷Flow每次collect开流，finally关闭，64KiB块；ByteArray与流限制不同；不应由Server重写VFS调用 |
| `ContextRetrievalException`、`ContextDreamingException`、`ContextBackup.kt` | 现有错误类型可直接映射；DreamingRunException有部分提交结果，Backup返回稳定URI，错误不能伪成功 |
| CT27 R2复核及CM6方案 | local-atomic-only；2156=2156、check132/132、WebDAV44为前置已报告证据，本轮未重跑 |

其中 `...` 表示对应模块的 `src/main/kotlin/com/github/noahshen/alcyone/context/memory/<模块>/` 包；测试位于同包 `src/test/kotlin`。设计中的 `ContextRuntime` 为逻辑名称，实际公共入口为 `AlcyoneContext`，不新增平行业务Runtime。

## 2. 裁定 D1～D12

| 编号 | 裁定 |
| --- | --- |
| D1 | 六个Read路径完全采用设计§7.9。Admin采用GET `/v1/admin/status`、POST `/v1/admin/dreaming`、POST `/v1/admin/backup`；Live/Ready采用设计§9.6。 |
| D2 | 三类Key严格互斥，Admin无隐式全权；已识别错类403，未知401。Ingestion保留runtime_id绑定；配置缺少某类Key可禁用该类访问，显式空值/未展开ENV/所有跨类重复必须拒绝。 |
| D3 | 复用现有Config/Registry和HTTP辅助；固定长度摘要+恒定时间比较，所有构造入口一致。拒绝未授权请求时0 SDK调用；认证不接受URL query中的Key或Forwarded身份。 |
| D4 | Ktor只作薄适配；Search严格JSON、有界body与字节校验，DTO格式保留API真实内容与时间语义；不将raw sources改写为内部Turn ID，不把序列化注解倒灌Core。 |
| D5 | Resource统一流式路由，不默认整体缓存或套用ByteArray限额；可选HTTP流上限必须实际计数。头提交前映射错误，提交后终止失败流；客户端取消必须到达collect/finally。 |
| D6 | 允许任务书规定的Server限额与超时配置；Ingestion限额来源保持实例真实配置。外部CE传播，自有deadline才504；不得把超时视作数据库已停止或已回滚。 |
| D7 | `checkReady()` 默认false，实际Runtime用受管executor轻量读权威表并复查RUNNING。缓存initialized/文件存在/单纯SELECT常量不能替代；不做每次quick_check、不调LLM/VFS。 |
| D8 | 保留getStatus无I/O兼容，新增getAdminStatus安全快照；管理指标必须来自既有Repository/真实计数。数据缺失显式unknown/null，不人为填0或CURRENT；数据库读取失败503，路径与自由文本异常不出HTTP。 |
| D9 | 新增managed Server组合负责Runtime启动/失败逆序清理/关闭，旧start(config,context)仍为外部所有权。close确认在途请求清理再释放Runtime，不能将stop超时等同于完成清理。 |
| D10 | VFS仅承接已实现的路径/命名空间/后端检查；没有逐用户ACL可供宣称。CT27 WebDAV publishNew拒绝保持；不在HTTP层降级写备份。 |
| D11 | B1仅Server鉴权/Read与测试。B2补最小API/Runtime健康和管理接缝、Admin/managed接线及全量验收；状态只读查询若缺Port，精确方法/SQL提交本Architect技术确认，不扩大到Schema/DML/治理算法。 |
| D12 | B1现已放行；B1独立技术验收后自动进入B2，CT28通过后推进已授权CT29。真实模型、Caddy/systemd生产实跑与长期cron结果必须按实际报告，不能由Fake或本机测试外推。 |

## 3. B2 开工前必须具体化的技术材料

Lead 的B2委派单列出：checkReady使用的现有executor/ReadContext方法；每项管理指标的真实来源和不可用表示；需要新增的只读Port/SQL（若有）；请求准入/在途登记/关闭等待时序；首块流预取与响应提交/取消释放机制。给出精确文件写集合及基线SHA，由Architect快速确认即可实施，不涉及用户再授权。

管理DTO不能只包装当前ContextStatus便宣称完成专题9全部指标。允许从LLM已有diagnostics/各调度器最小只读快照取得数据；如果部分来源根本没有记录，先如实列出缺口并选择最小补齐，禁止捏造“完整状态”。

## 4. 放行与验收责任

Lead 先落B1委派单，创建Worker按任务书实施并自测，随后独立初验；Architect按A01～A08的适用部分复核。B2收口需全量检查、模块@Test/XML核对、Schema与CT27保护、真实HTTP/SQLite/资源故障证据，CT28最终DONE由Architect裁定。

本轮仅创建任务书与本裁定，未修改生产/测试/构建，未创建子Agent，未执行Git写操作。全局授权状态由CM6 Lead统一维护，以用户最新连续授权为准。
