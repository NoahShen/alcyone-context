# Alcyone Context VFS

> 本次修改（2026-10-13，T30 复核修复 R1～R5）：更正事件类型枚举（FILE_DELETED / DIRECTORY_DELETED）；澄清 Python 环境依赖（系统需预装 Python，依赖包装于项目私有 venv）；澄清 LocalFsSampleTest 默认终端仅显示 BUILD SUCCESSFUL，详细 [sample] 输出位于 XML 报告的 system-out；调整开发格式化与日常检查入口说明。

Alcyone Context 的虚拟文件系统（VFS, Virtual File System）是一个面向 Agent 场景设计的内嵌式文件系统 SDK 库（Agent-native VFS Library）。它为上层 Agent 与 Context System 提供统一的抽象文件访问能力，隐藏底层存储后端（Local FS、WebDAV 等）的交互细节与协议差异。

---

## 1. 当前已交付能力与接入现状

- **已交付能力**：
  - 统一逻辑 URI 寻址（`alcyone://<namespace>/<path>`）；
  - 本地文件系统挂载（`MountBackend.LocalFs`）与远端 WebDAV 服务挂载（`MountBackend.WebDav`）；
  - 统一文件与目录操作：read（全量/流式）、write（三种写模式：CREATE_NEW / REPLACE_EXISTING / UPSERT）、stat、getNode、list、move、delete；
  - 节点身份持久化与元数据管理：Node ID 保持连续，Metadata 整体替换；
  - 进程内异步事件订阅（`FILE_CREATED`、`FILE_WRITTEN`、`FILE_MOVED`、`FILE_DELETED`、`DIRECTORY_DELETED`、`METADATA_UPDATED` 等）。
- **当前接入现状（未独立发布）**：
  - **当前仅供源码工程内引用**，尚未发布至 Maven Central 或独立私有仓库。
  - 请勿在构建脚本中配置未发布的 Maven / Gradle 坐标。独立产物消费验证将在后续里程碑任务（T31）中完成。
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

4. **全量测试回归**：
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

- **[SDK 使用指南](docs/vfs/SDK_使用指南.md)**：深入介绍初始化与挂载配置、Context System 架构分工、核心 API 语法、流式分块读取、事件通知与生命周期关闭。
- **[SDK 运行维护说明](docs/vfs/SDK_运行维护说明.md)**：包含状态库存储机制、实例独占与重开规则、离线备份与隔离验证恢复步骤、故障排查与 WebDAV 请求限制（方案④）。
- **[开发计划与进度](docs/开发计划与进度.md)**：查看项目整体演进里程碑与历史任务记录。

