# CT30 B3 R1 Architect 裁定

## 本次修改（2026-11-12，独立源码复核，放行最小证明与修复）

- 独立阅读 B3_Lead初验、LocalFsStorage 的 publishNew/sameTemp/open 与正式 PublishNew 测试；确认身份记录晚于复制/force，expected=null 直接为 true，同时影响发布前复核和 finally 清理。B3 保持 IN_REVIEW / R1，不批准 DONE。
- 本次是源码复核与技术裁定，未运行复现或回归，不冒称已实跑失败。允许原 Lead→唯一 Worker 在下列边界完成证明和修复；不再等待人工逐项授权。

## 1. R1-1 身份缺口：先复现，再最小修复

首次复现不需要新增生产钩子。在真实 Local FS 的受控 InputStream.read 中，把本次已创建的 staging 移到另一个路径，再在原 staging 路径写入不同内容的普通文件，随后抛 IOException；断言替换文件保留、目标不存在、输入恰好关闭一次。以同样的确定性交错覆盖 CancellationException 原样传播；测试自己用 finally 清理夹具，禁止并发 sleep 竞态。原代码应出现替换文件被删除的失败，保存原始 stdout/XML及退出码；如果未复现，只报告实际原因，不削弱断言。

生产最小方案：

1. CREATE_NEW 返回后，在 channel.use 的资源保护内、任何输入读取/复制/force 前取得 staging 的非空 fileKey，并立即保存为本次身份。后续完成复制、发布前与清理时必须比较原身份，不能把之后读到的替换文件 key 当成本次身份。
2. sameTemp 在 expected==null 或 actual==null 时返回 false；只接受非空 identity 的相等比较，保留现有 ByteArray 内容比较语义。只有已知身份且当前路径匹配时才允许清理。
3. fileKey 读取失败或返回 null 时，禁止 hard link/发布，关闭 channel 和 input；身份未知时保留临时路径供人工核对，不盲删。已创建 staging 的普通失败按既有 STORAGE_ERROR/PARTIAL 报告，公开消息不含物理路径；CE保持原样。无法证明归属的残留不得宣称已安全清理。
4. 身份已知时，正常短流/长流/取消继续清理自有 staging；路径换成另一普通文件后保留该文件，目标不得错误发布。输入 close、cleanup/sync 失败不得覆盖主异常，发布后失败保新与 effect 规则保持。
5. 可删除只赋值未读取的 handedOff，不新增公开状态/API/配置/strict mode，不改 Atomic 发布能力定义。此修复检查已观察到的身份，不能外推为抵御任意外部进程在最后检查与unlink/link之间持续替换的原子保证；不扩建安全文件系统框架。

为覆盖实际 key=null/读取失败，允许在现有 internal open 增加一个最小 fileKey 读取钩子，默认仍真实 NOFOLLOW_LINKS BasicFileAttributes.fileKey；限 publishNew 使用，不改公共 create 签名。须有正式 null/throw 用例，断言零目标发布、身份未知路径保留、输入关闭及失败语义。真实路径替换复现仍走实际 fileKey，不得全用 Fake 替代。

## 2. R1-2 默认目录同步能力正式证据

补一个实际默认 LocalFsStorage.create 的正式测试，不注入 directorySyncer；在真实临时挂载根独立执行目录 open+force，比较默认 capability 与实际探测结果，并运行真实发布/重开相关既有测试。在当前 macOS arm64 验收环境须有明确 capability=true 断言与输出；其他平台不无条件宣称 true，不引入skip来凑测试差额。

如果当前 macOS 真实探测不支持，保留失败事实并报告，不改为“默认返回true”，不弱化 false 的既有原子非durable分支。真实断电/内核崩溃/Linux硬件flush仍not-run。

## 3. 写集合与角色

- 唯一生产文件：vfs/storage/.../LocalFsStorage.kt，仅上述身份记录/比较、必要internal读取钩子、无用变量清理。
- 正式测试：LocalFsPublishNewTest.kt、LocalFsSafeReplaceTest.kt（或同模块一个限定能力测试）；不扩全仓测试重构。
- 文档：B3 委派、Lead初验、恢复点与本次交付证据。每次文档修改顶部补本次修改说明。
- Server 单文件验收修复沿用 B3_接任Lead任务.md 的最小例外；现有两个Unit、auth字段、/health/live保留，本轮不再扩修Server。最终格式若要求机械排版，仅该文件允许且列明diff；生产Server禁止修改。
- Storage.kt/WebDavStorage.kt、B1/B2 journal/event/cursor、所有Schema/migration、公共API、Runtime/Context生产/deploy冻结。无Git写操作，禁止reset/checkout/clean。B4/CT31/CT32未放行。
- Architect只做裁定与独立复核；Lead负责唯一Worker与独立初验，不直接写生产/测试。沿用Lead fcf0cd10与Worker 1d3edffa。

## 4. 验收顺序与证据要求

1. Worker先在旧生产SHA916c87c8…上执行新反例，保留红灯原因；再完成最小修复及上述正式测试，运行格式、storage定向/模块与受影响真实VFS恢复/发布测试，保存SHA。
2. Worker冻结并交付。Lead亲自审查diff及反例，必要独立定向验证；按原B3验收要求由Lead执行一次最终 ./scripts/check，完整保留stdout/stderr、准确退出码、源码SHA、14模块源码/XML/失败/跳过计数。避免Worker与Lead各跑一次重复全量；若最终发现新失败，按具体失败处理后必要重跑。
3. 可用任务专属日志目录和带pipefail的tee，或stdout/stderr重定向加独立退出码文件；工具调用不要阻塞超过60秒无更新。不依赖系统JShell或全局JDK，全部使用项目工具链。
4. 旧2318合并XML与历史Worker自述冲突继续保留为历史证据，本次最终日志应消除当前验收不可追溯问题。增加用例后实际总数重算，不固定旧2318。
5. Lead报告必须列红→绿测试、真实macOS能力、身份未知残留规则、文件SHA、未改边界、最终日志和not-run；提交Architect独立复核后才能DONE。若遇边界外缺陷，给精确证据，不循环调查或擅自扩范围。
