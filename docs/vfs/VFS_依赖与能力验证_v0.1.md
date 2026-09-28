# VFS 依赖与能力验证 v0.1

## 本次修改（2026-09-28，T04 审阅通过）

- 用户确认验证结果、平台范围与文件大小限额；保留后续实现的适配验收项。涉及状态说明及第 1、8 项。

状态：已审阅通过（2026-09-28）；对应 T04，最小验证已通过。依据已通过的 T01～T03，验证工程只确认依赖可行性，不代表 VFS SDK 已实现。

### 1. 依赖组合

以下固定组合已完成最小验证并获用户确认，正式工程 T05 沿用这些版本。Kotlin / Gradle 范围依据 [Kotlin 官方兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)，具体集成结果以本机编译为准。

| 组件 | 本轮验证版本 | 用途 |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1 | JVM 21 toolchain |
| Gradle / Kotlin | 8.14.3 / 2.2.21 | Kotlin DSL、编译与序列化插件 |
| SQLDelight | 2.1.0 | 生成查询接口及 JVM SQLite Driver |
| OpenDAL Java | 0.50.6 + `osx-aarch_64` native | Local FS / WebDAV |
| UUID Creator | 6.1.1 | `UuidCreator.getTimeOrderedEpoch()` 生成 UUIDv7 |
| Coroutines / Serialization JSON | 1.10.2 / 1.9.0 | 挂起 API 与 Metadata JSON |
| SLF4J | 2.0.17 | SDK 日志接口；具体日志绑定由宿主提供 |

例：macOS Apple Silicon 同时加载 `org.apache.opendal:opendal:0.50.6` 与相同版本的 `osx-aarch_64` 包，公共 API 不暴露这些类型。发布坐标见 [Maven Central 原始发布目录](https://repo.maven.apache.org/maven2/org/apache/opendal/opendal/0.50.6/)；UUID 用法见 [项目官方说明](https://github.com/f4b6a3/uuid-creator)。

### 2. 验证环境与平台范围

本机为 macOS 26.6.2 / arm64，系统默认 Java 11 和 Gradle 8.2.1；本轮另在 `/tmp/alcyone-t04/` 准备 JDK 21、Gradle 和 Python 虚拟环境，不改系统默认配置。首版先支持此平台，Linux / Windows 需分别验证 native 加载后再列为已支持。

例：macOS 验证通过不能推导 Linux ARM64 已通过，即使仓库发布了相应 native 包。

### 3. SQLite 与基础依赖

最小工程通过 SQLDelight 生成查询代码，并验证路径唯一约束、Node / Event 一起提交和事务回滚；正常关闭后重新打开数据库须能读取已提交状态。UUIDv7 检查版本位、variant 和样本重复情况，协程、JSON 序列化与日志接口一并编译运行，不验证崩溃恢复。

例：先插入 Node，再故意触发 Event 主键冲突，整个事务回滚，Node 数量不增加。SQLDelight 配置依据 [官方 JVM SQLite 文档](https://sqldelight.github.io/sqldelight/2.1.0/jvm_sqlite/)。

### 4. 最小验证与复现

验证代码保留在 [tools/validation/t04](../../tools/validation/t04/)，使用真实 SQLite、OpenDAL 和仅监听本机的临时 WebDAV 服务。服务使用 WsgiDAV 4.3.3 / Cheroot 10.0.1，测试结束即关闭并移除临时数据；它验证 HTTP WebDAV 基本行为，不能代表所有远端服务。

例：Local FS 文件复制到 WebDAV，读回核对字节后删除源，再反向执行一次。

实测结果：Kotlin 编译、SQLDelight 代码生成、SQLite 唯一约束 / 回滚 / 关闭重开、两类后端的基本文件操作及有界流读取、双向复制删除、10,000 个 UUIDv7 样本检查均通过。保留 [原始验证输出](../../tools/validation/t04/result.txt)；这是同进程关闭重开数据库，不是进程中断恢复测试。

准备 JDK 21、Gradle 8.14.3 和 Python 环境后，可运行以下命令；路径替换为本机工具位置，脚本会自动启动和关闭测试服务：

```sh
python3 -m venv /tmp/alcyone-t04/venv
/tmp/alcyone-t04/venv/bin/pip install -r tools/validation/t04/requirements.txt
JAVA_HOME=/tmp/alcyone-t04/jdk/jdk-21.0.12.1+1/Contents/Home \
GRADLE_USER_HOME=/tmp/alcyone-t04/gradle-cache \
python3 tools/validation/t04/run_probe.py \
  --gradle /tmp/alcyone-t04/gradle-8.14.3/bin/gradle \
  --wsgidav /tmp/alcyone-t04/venv/bin/wsgidav
```

### 5. 后端能力与适配边界

两类后端均提供基本读写、stat、list、createDir、delete 和文件 rename，但这不等于完整满足 T02。以下差异已实测确认，应在 Adapter / Core 编排中补齐，非覆盖写入和真实远端特殊错误仍需后续验收。

| 实测差异 | 首版适配方式 |
| --- | --- |
| 两类后端的目录 rename 返回 IsADirectory | 目录移动采用逐层列举、复制完整目标（含空目录）、确认后删除源；由 T22 验证 |
| 两类后端均不声明 recursive list | 使用逐层 list 遍历，不能假设一次 list 返回整棵子树 |
| 删除不存在文件均返回成功 | VFS 先检查存在性，按 T02 返回 NOT_FOUND |
| Local FS 跟随链接并读到挂载根外文件 | Adapter 使用本地路径检查拒绝符号链接；T12 必须验证，当前依赖本身不提供该保证 |

例：VFS 删除不存在路径应返回 NOT_FOUND，即使底层 delete 将其当作成功，也应由适配层补齐检查。

### 6. 文件大小与挂起调用

首版 ByteArray 读写默认上限为 16 MiB，并允许 Runtime 配置；超限在读入完整内容前或写入 Storage 前返回 LIMIT_EXCEEDED。同步 OpenDAL 调用放到 I/O 执行环境，读取使用有界流或分段读取落实限额，不能仅信任可能过期的 stat 大小；具体实现留 T12 / T15。

例：默认配置读取 20 MiB 文件时拒绝，上层显式提高限额后才允许；首版跨 Mount 复制也按单文件限额执行，须在删除源前完成复制与确认；更大文件的流式复制后续扩展。

### 7. 开发验收清单

以下在对应开发任务中覆盖完整行为，沿用 T02 的场景编号，不在 T04 重复实现 VFS。中断续做、重启修复和可靠事件重放不加入首版验收。

| 范围 | 主要验收例子 | 对应任务 |
| --- | --- | --- |
| 路径与挂载 | 编码、挂载覆盖、符号链接拒绝、目录边界 | T06、T09、T12 |
| 本地文件操作 | 三种写入模式、大小限制、缺失父目录、完整 list | T12、T13、T15、T16 |
| 状态与事件 | 唯一路径、Metadata、事务回滚、通知失败不回滚 | T11、T14、T17、T23、T26 |
| 移动 | 同 / 跨 Mount、空目录、子 Node ID 保留、部分失败 effect | T20～T23 |
| WebDAV | 真实服务、凭据拒绝、网络失败、目录及跨后端移动 | T24、T25 |
| 生命周期 | 单实例、并发、正常重启读取、取消与资源释放 | T18、T27 |

### 8. 结论与下一步

本轮版本组合在 macOS arm64 上通过最小验证，可作为 T05 的构建基线；正式 Schema、Adapter 与 SDK 仍未实现。macOS arm64 平台范围和 16 MiB 默认值已获用户确认；未验证的能力需在对应任务保留显式验收项。

例：T04 通过后，T05 创建五个 VFS 模块及集成测试模块，复用本轮版本，不复制验证工程作为生产代码。
