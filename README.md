# Alcyone Context VFS

> 本次修改（2026-10-14，T32 首版交付记录与后续需求整理及验收）：完善文档索引与交付口径，增加《首版交付记录》与《后续需求清单》入口，同步当前六模块本地发布与独立消费闭环状态。

> 历史修改（2026-10-13，T31 SDK 打包与本地消费开发及验收）：更新接入现状为“已在 macOS arm64 上完成项目内本地仓库产物（0.1.0-SNAPSHOT）消费验证，尚未公开发布”；补充本地仓库配置、依赖坐标与 JDK/JVM 要求；新增 SDK 本地打包与独立消费验证命令。

Alcyone Context 的虚拟文件系统（VFS, Virtual File System）是一个面向 Agent 场景设计的内嵌式文件系统 SDK 库（Agent-native VFS Library）。它为上层 Agent 与 Context System 提供统一的抽象文件访问能力，隐藏底层存储后端（Local FS、WebDAV 等）的交互细节与协议差异。

---

## 1. 当前已交付能力与接入现状

- **已交付能力**：
  - 统一逻辑 URI 寻址（`alcyone://<namespace>/<path>`）；
  - 本地文件系统挂载（`MountBackend.LocalFs`）与远端 WebDAV 服务挂载（`MountBackend.WebDav`）；
  - 统一文件与目录操作：read（全量/流式）、write（三种写模式：CREATE_NEW / REPLACE_EXISTING / UPSERT）、stat、getNode、list、move、delete；
  - 节点身份持久化与元数据管理：Node ID 保持连续，Metadata 整体替换；
  - 进程内异步事件订阅（`FILE_CREATED`、`FILE_WRITTEN`、`FILE_MOVED`、`FILE_DELETED`、`DIRECTORY_DELETED`、`METADATA_UPDATED` 等）。
- **当前接入现状（已完成本地消费验证，尚未公开发布）**：
  - **本地仓库验证已闭环**：已在 macOS arm64 上完成项目内本地仓库（`.local/sdk-repository/`）产物（版本 `0.1.0-SNAPSHOT`）由独立最小 JVM 工程消费验证，涵盖 Runtime 核心完整生命周期与正常重开；
  - **尚未公开发布**：尚未发布至远端 Maven Central 或外部私有仓库，请勿在无本地仓库源的外部工程中依赖该坐标；
  - 规划中的 Memory 管理、Git 同步、自动故障恢复等高级特性尚未完成，不属于当前已交付范围。

---

## 2. 平台支持与运行环境

- **macOS arm64**：**已验证支持**（本工程常规测试套件与真实 WebDAV 集成套件均在此环境下全量通过）。
- **Linux x64 / Linux arm64**：OpenDAL 底层与构建配置已保留，但**当前阶段未验证**，不作支持承诺。
- **其他平台 / 架构**：当前构建与测试脚本显式拒绝执行。
- **环境依赖**：
  - JDK 与 Gradle：通过 `./scripts/dev bootstrap` 安装并隔离在项目私有 `.local/` 目录中，无需全局预装。
  - Python（仅 WebDAV 测试需要）：需系统已有可用 Python 3（可使用 `WEBDAV_PYTHON` 环境变量指定解释器路径），其第三方依赖包（如 WsgiDAV）仅安装在项目私有虚拟环境（`.local/webdav/venv`），不修改系统全局 Python 环境。

---

## 3. 项目私有工具链与快速验证

本项目使用自包含的私有工具链脚本（位于 `./scripts/`）：

1. **环境准备（Bootstrap）**：
   ```bash
   ./scripts/dev bootstrap
   ```
   下载并就绪项目私有的 JDK 与开发环境。

2. **日常构建与检查**：
   ```bash
   ./scripts/check
   ```
   执行全量源码构建、代码规范检查与单元/集成测试。如需在开发过程中自动格式化代码，可运行 `./scripts/dev gradle spotlessApply`。

3. **运行最小可运行快速入口（Local FS 生命周期样例）**：
   直接执行集成测试中的 `LocalFsSampleTest`，演示创建配置、写入、读回、状态查询、元数据修改、列举、流式读取与安全关闭全流程：
   ```bash
   ./scripts/dev gradle :integration-tests:vfs:test --tests "*LocalFsSampleTest*" --console=plain
   ```
   - 终端默认输出 Gradle 执行结果（如 `BUILD SUCCESSFUL`）。
   - 详细生命周期观测输出（如 `[sample] wrote ...`、`[sample] read ...` 等）记录在测试结果 XML 报告的 `<system-out>` 节点中：
     `integration-tests/vfs/build/test-results/test/TEST-com.github.noahshen.alcyone.context.vfs.integration.runtime.LocalFsSampleTest.xml`。

4. **SDK 本地打包与独立消费验证**：
   ```bash
   ./scripts/t31-consumer-check
   ```
   自动清空并重新打包 6 个 SDK 模块（`alcyone-common`、`alcyone-vfs-api`、`alcyone-vfs-core`、`alcyone-vfs-storage`、`alcyone-vfs-persistence`、`alcyone-vfs-runtime`）至项目内本地仓库（`.local/sdk-repository/`），并触发独立消费工程（`tools/validation/t31-consumer`）仅通过 Maven 坐标依赖运行端到端验证。

5. **全量测试回归**：
   - 常规测试套件（752 例）：
     ```bash
     ./scripts/check
     ```
   - 真实 WebDAV 服务的网络集成套件（43 例，基于项目私有虚拟环境中自启动的 WsgiDAV 服务）：
     ```bash
     ./scripts/webdav-check
     ```

---

## 4. 后续指南与技术文档

- **[首版交付记录](docs/vfs/首版交付记录.md)**：集中列出 v0.1.0-SNAPSHOT 的版本基线、六模块坐标、项目内本地仓库、已交付核心能力、支持边界与已接受限制及验证入口。
- **[后续需求清单](docs/vfs/后续需求清单.md)**：记录既有计划扩展需求（E01～E05）与后续工程候选（E06～E09）的来源、影响、启动条件与验收目标。
- **[SDK 使用指南](docs/vfs/SDK_使用指南.md)**：深入介绍初始化与挂载配置、Context System 架构分工、核心 API 语法、流式分块读取、事件通知与生命周期关闭。
- **[SDK 运行维护说明](docs/vfs/SDK_运行维护说明.md)**：包含状态库存储机制、实例独占与重开规则、离线备份与隔离验证恢复步骤、故障排查与 WebDAV 请求限制（方案④）。
- **[开发计划与进度](docs/开发计划与进度.md)**：查看项目整体演进里程碑与历史任务记录。

