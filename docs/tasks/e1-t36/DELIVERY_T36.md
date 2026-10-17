# T36 订阅集成与可选插件交付

## 本次修改（2026-10-17，T36 订阅集成与可选插件交付）

- 通过真实 AlcyoneVfs 与 GitBackupPlugin、SQLite/Local FS、本地 bare remote 组合验证，不绕过公开订阅接口。
- 新增 integration-tests/vfs/src/test/.../integration/gitbackup/GitBackupPluginIntegrationTest.kt，覆盖 A01/A02/A03/A07：
  * 订阅先于操作，事件驱动（无 requestSync 救场）；
  * 新增/覆盖/删除/移动核对远端路径与 blob 内容；
  * 读取/Metadata 不误触发 Git（HEAD 稳定性断言，仅说明未新增提交，不推断未同步）；
  * 物理外部变更通过手动/启动入口补同步并验证远端内容；
  * 关闭插件后 VFS 继续读写，残留回调不触发 Git；
  * 重新创建插件补当前变化，不宣称事件重放；
  * 所有 remote 为本地 bare remote（git init --bare），不接触真实远端。
- 根 build.gradle.kts publishableArtifacts 增加 :vfs:git-backup → alcyone-vfs-git-backup，单独可选坐标。
- integration-tests/vfs/build.gradle.kts 增加 testImplementation(project(":vfs:git-backup"))。
- 新建 tools/validation/t36-plugin-consumer，显式依赖插件坐标 alcyone-vfs-git-backup:0.1.0-SNAPSHOT，完成本地 Git 闭环（A04）：
  * 对远端树/内容使用 check 硬断言；
  * 关闭后 VFS 可读，残留回调不触发 Git；
  * 重新创建插件补当前变化；
  * 使用 waitFor 有界等待，替代固定 sleep；
  * finally 清理本次资源和临时目录。
- 原六模块 SDK 独立消费（t31-consumer）不拉入插件，保持零改动。
- 不接触真实远端、不公开发布、不新增平台承诺；所有 remote 均为本地 bare remote。

## 使用说明

### 1. Gradle 坐标与仓库

先在本项目执行 `./scripts/t31-consumer-check`，将当前产物发布到项目 `.local/sdk-repository` 并验证原 Runtime 消费。以下独立工程使用 JDK 21；本阶段已验证平台为 macOS arm64。

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    application
}
repositories {
    maven {
        url = uri("/abs/path/to/alcyone-context/.local/sdk-repository")
        content { includeGroup("com.github.noahshen.alcyone") }
    }
    mavenCentral { content { excludeGroup("com.github.noahshen.alcyone") } }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.github.noahshen.alcyone:alcyone-vfs-runtime:0.1.0-SNAPSHOT")
    implementation("com.github.noahshen.alcyone:alcyone-vfs-git-backup:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("MainKt") }
```

把仓库路径改成本项目实际位置；坐标未公开发布。现有可复现消费工程见 `tools/validation/t36-plugin-consumer`，其中工具链及版本来源已配置。

### 2. 准备专用 Git 工作区与本地测试远端

替换下列绝对路径；状态数据库放在工作区之外。首次准备执行一次，不要对已有仓库重复添加 origin。

```bash
mkdir -p /abs/path/to/workspace /abs/path/to/state
git init --bare -b main /abs/path/to/remote.git
git -C /abs/path/to/workspace init -b main
git -C /abs/path/to/workspace config user.name "Your Name"
git -C /abs/path/to/workspace config user.email "you@example.com"
git -C /abs/path/to/workspace remote add origin /abs/path/to/remote.git
```

不要求预先创建空提交。插件会提交首次文件变更；测试中的基线空提交仅用于测试夹具。`remoteName` 是已配置的 remote 名称，不是 URL；实际 URL 由 `git remote add` 设置。真实远端认证由宿主预先配置，本轮仅验证本地 bare remote。

### 3. Mount 与工作区

示例将 `/resources` Mount 的物理根与 `repositoryRoot` 设为同一 `Path`。插件通过现有订阅入口接入，不修改 Runtime 的创建或关闭逻辑。

### 4. 完整 Kotlin 示例

保存为 `src/main/kotlin/Main.kt`，替换路径后运行。该示例保持宿主存活，观察日志确认同步结果后输入回车结束；`requestSync()` 仅发起请求，不等待完成，关闭也不保证补推。非交互部署应使用宿主自身生命周期替代 `readlnOrNull()`。

```kotlin
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupConfig
import com.github.noahshen.alcyone.context.vfs.gitbackup.GitBackupPlugin
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Duration

fun main() = runBlocking {
    val workspace = Path.of("/abs/path/to/workspace")
    val backup = GitBackupPlugin(
        GitBackupConfig(
            repositoryRoot = workspace,
            eventRoots = setOf(VfsUri.parse("alcyone://resources")),
            branch = "main",
            remoteName = "origin",
            quietPeriod = Duration.ofSeconds(60),
            cron = "0 3 * * *",
        ),
    )
    val vfs = AlcyoneVfs.create(
        VfsRuntimeConfig(
            stateDatabase = Path.of("/abs/path/to/state/state.db"),
            namespaces = setOf("resources"),
            mounts = listOf(MountConfig(VfsPath.parse("/resources"), "resources", workspace)),
        ),
    )
    try {
        vfs.subscribe(backup.eventConsumer)
        backup.start()
        vfs.write(VfsUri.parse("alcyone://resources/a.txt"), "hello".toByteArray())
        backup.requestSync()
        println("查看同步终结日志；按回车关闭宿主。")
        readlnOrNull()
    } finally {
        try {
            backup.close()
        } finally {
            vfs.close()
        }
    }
}
```

### 5. Cron 配置示例
五字段表达式，本机时间，如 `"0 3 * * *"`（每天 03:00），不含 zoneId。

### 6. 身份 / 认证 / SSH 覆盖边界
VFS 不保存凭据。Git 身份/认证由宿主配置（`~/.gitconfig` 或环境变量）。插件强制 `GIT_SSH_COMMAND=ssh -o BatchMode=yes`，会覆盖宿主该变量；若宿主使用定制 SSH 包装，请注意此默认行为，不宣称无条件完整继承宿主定制 SSH。

### 7. subscribe / start / manual / close
- `vfs.subscribe(backup.eventConsumer)`
- `backup.start()`
- `backup.requestSync()` 手动触发
- `backup.close()`：close 后 VFS 仍可读，残留回调无动作，重新创建插件可补当前变化

### 8. 宿主日志留存与失败后触发
插件只写 SLF4J 日志，留存/轮转由宿主提供。失败后等待下次正常触发（Cron/手动/启动），不自动重试。

## 验证记录（按实际断言回填，不宣称 A01～A08 全达成）

- 集成测试：`GitBackupPluginIntegrationTest`
  - 事件订阅 + 新增/覆盖/删除/移动 + 物理外部变更 + 关闭后 VFS 可用 + 重开插件补变化 + Git 阻塞期间 VFS 写完成
- 消费程序：`t36-plugin-consumer`（坐标解析 + 本地 Git 闭环）
- 常规源码 @Test：828 处；常规 XML：828 例；WebDAV：43 例单列；模块：73
- 全工程 XML：871 例（含 WebDAV 43，Failures=0, Errors=0, Skipped=0）
- 历史基线：752 / 43 仅作比较基线

## 已知边界
- 不接触真实远端，仅本地 bare remote
- 不新增公开状态模型、重试、.git 保护
- VFS 保存不等待 Git
