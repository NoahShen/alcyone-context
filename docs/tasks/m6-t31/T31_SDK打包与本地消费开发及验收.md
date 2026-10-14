# T31 SDK 打包与本地消费开发及验收

## 本次修改（2026-10-14，203b903 独立收口）

- R2 插件版本单一来源确认关闭，R1 保持关闭；本轮独立一键消费、clean 编译运行及 752 / 43 回归全部通过，源码/XML 差额零。T31 **DONE**，M6 IN_PROGRESS，不开始 T32。涉及当前状态与验收结论；详细证据见 T31 架构与技术复核第 7 节，版本一致不等同未来 ABI 兼容保证。

## 本次修改（2026-10-14，T31 复核第 6 节收尾）

- 消费工程 `build.gradle.kts` 插件改用 `alias(libs.plugins.kotlin.jvm)`，彻底消除 Kotlin 插件版本硬编码，与 `gradle/libs.versions.toml` 保持单一来源；
- `run-consumer.sh` 注释规范为识别平台与已实跑验证平台（macOS arm64）；
- 明确临时目录清理失败仅输出告警，不使已通过的断言失败。T31 保持 **IN_REVIEW**。

## 历史修改（2026-10-13，T31 复核修复 R1/R2）

- 补齐消费工程工具链隔离：增加 `gradle.properties`（禁用 auto-detect / auto-download，配置受控 JVM 参数）、限定 Kotlin daemon runFilesPath 在 `.local/tmp/kotlin-daemon`，并在 `run-consumer.sh` 中复用 `install.sha256` 清单校验；
- 落实 SDK 仓库排他与依赖版本单一来源：消费工程通过 Version Catalog 读取 Kotlin 插件及第三方库版本，`mavenCentral` 排除 SDK group；
- 补充分块 openStream 的 SHA-256 哈希比对、移动事件目标 URI 断言与临时目录清理返回值检查；
- 明确标注 Mac 发布产物 native classifier 为 `osx-aarch_64` 固定，收窄发布目标为“本任务命令仅发布至项目目录”。

## 历史修改（2026-10-13，T31 开发完成）

- 完成六个必要模块（common、vfs/api、vfs/core、vfs/storage、vfs/persistence、vfs/runtime）的 Maven 发布配置，目标限定于项目内本地仓库 `.local/sdk-repository/`；
- 建立独立最小 JVM 消费工程（`tools/validation/t31-consumer`），通过 Maven 坐标依赖成功执行公开 Runtime 完整生命周期及重开一致性验证；
- 交付一键打包与消费验证脚本 `./scripts/t31-consumer-check`；
- 更新 README.md、SDK_使用指南.md 以及交付/使用/验收文档，主工程常规 752 例与 WebDAV 43 例全绿。

状态：**DONE**。203b903 独立收口通过，见复核第 7 节；M6 仍 IN_PROGRESS，不开始 T32，不推送、打标签或公开发布。

依据：[开发计划](../../开发计划与进度.md)、[T30 收口复核](../m6-t30/T30_架构与技术复核.md#7-484a49d-收口复核2026-10-13)、[SDK 使用指南](../../vfs/SDK_使用指南.md)。保留当前未提交的 T30 复核文档，不覆盖或将其误记为本次实现。

## 1. 目标与最小范围

让一个不属于本工程的 JVM 调用方，仅依赖本地仓库中的 SDK 坐标即可编译并运行公开 Runtime。完成打包、依赖元数据和消费验证即可，不新增 VFS 功能，不调整已确认的 API、事务、恢复或事件契约。

例：消费方声明一个 runtime 依赖，创建 Local FS 挂载后写入、读取、设置 Metadata、关闭并重开；不能用 project(...)、includeBuild 或源码目录替代产物。

## 2. 开发内容

### 2.1 坐标、版本与发布模块

沿用模块职责，发布 common、vfs/api、vfs/core、vfs/storage、vfs/persistence、vfs/runtime 六个必要模块；根聚合工程与 integration-tests 不作为 SDK 产物。建立一处版本与坐标配置，生成普通 JAR、POM 与 Gradle 模块元数据，优先使用现有 Gradle 发布能力，不做 fat JAR 或自定义插件框架。

本轮建议采用以下**本地验证坐标**（实现前可因现有约定做小幅调整，但须统一记录并核对模块间引用）：

| 项 | 本轮建议值 |
| --- | --- |
| group | com.github.noahshen.alcyone |
| version | 0.1.0-SNAPSHOT |
| common | alcyone-common |
| vfs/api / core | alcyone-vfs-api / alcyone-vfs-core |
| vfs/storage / persistence | alcyone-vfs-storage / alcyone-vfs-persistence |
| vfs/runtime | alcyone-vfs-runtime |

这些值不表示远端坐标已发布或命名空间所有权已验证。主入口是 runtime；common 同级职责与 Runtime 公开 VfsLimits 所需的 Core 依赖必须保留，不为压缩依赖表重排架构。源码包可按现有 Gradle 能力生成，不要求本轮新增文档生成器、签名或 Maven Central 接入。

### 2.2 项目内本地仓库与工具链

产物写入仓库内忽略目录，例如 `.local/sdk-repository/`；提供明确的构建发布和消费检查命令，重复执行不会混入旧版 SDK 产物。不要使用默认用户 `~/.m2` 作为交付目的地，也不要修改系统 JDK、用户 Gradle 配置或 shell 环境。

例：验证前仅清理本任务专属产物目录，使用项目私有 JDK / Gradle 启动生产者与消费者。消费者的 JVM、编译器、Gradle 缓存与 Kotlin daemon 同样受项目环境管理，不能因它是独立工程就退回系统工具链。

### 2.3 传递依赖与元数据

检查生成的 POM / Gradle 元数据，而不只看仓库文件是否存在：内部模块应引用正确的发布坐标与版本，公开类型编译所需依赖应可见，OpenDAL、SQLite/JDBC、SQLDelight、UUID 等运行依赖应能传递解析。SDK 不携带测试依赖或 SLF4J 实现绑定；消费方可自行选择日志绑定，不为验收方便把它加进 SDK。

例：只声明 runtime 后应能使用 VfsUri、NodeMetadata 与 VfsLimits，运行时能打开真实 SQLite 和 Local FS；不能让消费方再手工补 storage/persistence/native JAR 来掩盖元数据缺失。正常 Kotlin/协程调用所需的消费者语言插件或直接业务依赖可以保留，但必须解释来源。

### 2.4 native 平台边界

当前 storage 构建通过生产者 OS/CPU 选择 OpenDAL classifier。发布后要检查 native 依赖是否被写成生产者平台的固定值，并明确这种产物的适用范围；**构建脚本能识别 Linux 不等于从 Mac 发布的元数据能随消费者平台自动切换**。

本轮只需在已承诺支持的 macOS arm64 上闭环；若采用固定 Mac classifier 的本地产物，明确标注 macOS arm64 本地验证，避免混称通用跨平台包。不要求平台变体框架或 Linux 新实跑；如果调整 classifier/依赖策略，应保持未知平台拒绝、记录实际解析结果，不静默回退到错误 native。SQLite native 也由真实打开数据库佐证，不能只验证 OpenDAL JAR 文件名。

### 2.5 独立最小 JVM 消费工程

提供可复现的独立 Kotlin/JVM 工程或生成模板：单独 settings/build 文件，不加入根工程 include，也不引用源码、构建 classes 目录或生产者 project 依赖。内部 SDK 坐标只从本任务本地仓库解析，第三方依赖可使用既有公开仓库；用仓库内容限制或等价做法避免碰巧从其他仓库取到同名旧 SDK。

一次成功流程覆盖 create → subscribe → write/read → stat/list → set/getMetadata → 分块 openStream 并关闭完整 result → move 保持身份 → delete → finally close；另保留一个未删除节点做正常重开 ID/Metadata/内容核对。使用临时目录和公开入口，通知等待明确目标/类型并有界；无需搬运完整集成测试矩阵，不通过内部注入或 SQL 操作驱动主流程。

例：用户只运行消费检查命令就能看到通过标记；断言失败必须导致非零退出，不能只 println 结果。保存完整源码及初始化/清理逻辑，不能依赖仅在 `/tmp` 存在的未归档脚本。

### 2.6 证明消费的是产物并回写文档

保存消费方解析到的 SDK 坐标、来源仓库、运行 classpath 和关键 native 依赖，核对不含生产者源码/classes 路径；至少一次使用空的本任务 SDK 仓库构建发布后再消费，避免缓存造成假阳性。消费者构建仅执行自己的编译/运行，不能暗中接入生产者 composite build。

更新 README、SDK 指南中的接入现状：从“仅源码工程内调用”改成“已验证项目内本地仓库产物消费、尚未远端发布”，列出准确命令、坐标、JDK/JVM 要求及 native 平台限定。方案④、无 ACL、非可靠事件、非自动恢复边界不变，不写成支持 Maven 所有消费场景或所有 Linux 平台。

## 3. 实施顺序

| 步骤 | 工作 | 完成标志 |
| --- | --- | --- |
| S1 | 盘点六模块依赖和 native 选择，确定本地坐标与目录 | 发布清单、版本来源、平台限定清晰 |
| S2 | 配置最小发布与生成元数据 | 空本地仓库生成完整内部依赖链 |
| S3 | 独立消费工程编译与执行 | 仅靠产物完成公开流程与正常重开 |
| S4 | 核对元数据/依赖、回归及文档 | 可复现命令、断言和证据归档，转 IN_REVIEW |

范围以发布配置、验证入口/样例和文档为主；若发现必须改生产实现才能消费，先写清具体原因与最小修正，不顺手重构模块或扩充功能。

## 4. 验收标准

| 编号 | 必须满足 | 证据 |
| --- | --- | --- |
| A01 坐标与范围 | 六个必要模块有唯一一致坐标/版本，测试与根聚合不发布 | 发布配置、实际仓库清单及模块对应表，无 unspecified 或错误内部坐标 |
| A02 本地可复现 | 项目内仓库可从空状态生成；私有工具链与清理范围正确 | 完整命令/日志，JDK/Gradle 来源，产物目录；未写默认用户 Maven 仓库 |
| A03 依赖元数据 | 编译/运行依赖完整，无测试依赖和强制日志绑定 | 实际 POM / module 文件核对与消费者 compile/runtime 依赖结果；不手补 SDK 内部依赖 |
| A04 独立消费 | 独立 settings 工程仅通过坐标使用 SDK | 完整样例、仓库来源/classpath，无 project/includeBuild/源码/classes 捷径，失败退出非零 |
| A05 公开调用闭环 | 文件操作、Metadata、流完整关闭、通知代表和重开身份正确 | 实际断言与执行输出，清理在 finally；区分样例验证与既有正式测试 |
| A06 native 与平台 | OpenDAL 与 SQLite 在消费进程实际加载执行，平台声明不越界 | native classifier/解析路径、真实 I/O/建库与重开；说明生产者固定平台元数据的限制 |
| A07 文档与边界 | 接入说明准确区分本地消费与远端发布 | README/SDK 指南同步；无发布凭据、签名、推送/标签、跨平台或网络超时虚假承诺 |
| A08 回归与归档 | 原有行为与模块边界保持，证据可复现 | SHA、环境、命令、日志/报告、源码/XML 计数及新增样例单列；开发自测和独立复核分开 |

构建依赖与发布配置变动后运行 `./scripts/check` 和 `./scripts/webdav-check`，基线为 752 / 43，新增正式测试如实计数；消费 smoke 不冒充 JUnit 用例。普通配置不写镜像实现的测试，重点以生成元数据与独立运行证明效果；不要求给每个模块另建消费者。

## 5. 交付与后续

交付可审查的发布配置、完整消费样例/验证入口，以及本目录 `T31_交付文档.md`、`T31_验收记录.md`、`T31_使用说明.md`。记录产物清单、坐标、平台限制、复现步骤及测试结果；每份修改文档前部说明本次内容与涉及章节。

本轮安排不包含实现、构建发布或消费实跑。开发 Agent 完成后交回复核；通过 T31 也不代表 M6 自动完成，T32 需用户另行启动，不进行远端发布或额外授权流程。
