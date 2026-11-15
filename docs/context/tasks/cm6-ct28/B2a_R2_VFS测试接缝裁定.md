# CT28 B2a-R2 VFS 计数测试接缝裁定

## 本次修改（2026-11-11，批准最小 internal ManagedVfs 接缝）

- 确认现有 internal Factory 无 VFS 注入、`AlcyoneVfs` final 且构造私有、`AssemblyProbe` 不含 Context 的 Ready 调用计数，原三个测试文件无法形成真实 Factory 路径的零 VFS 调用证据；Worker 暂停时生产/测试 SHA 未变。
- **不批准**简单把 Impl 的 `AlcyoneVfs` 字段改为公共 `Vfs`：`vfs/api/Vfs` 没有 `close()`，直接替换会破坏既有释放路径，也不能用类型判断跳过包装实例关闭。
- 批准仅在 Context Runtime 两个既有生产文件内增加 internal `ManagedVfs : Vfs` 关闭能力与默认 VFS 工厂；公共 `create`、VFS 模块、配置和生命周期顺序不变。R2 仍是补测任务，B2a 未 DONE，B2b/c 未放行。

## 1. 精确写集合

新增允许修改的生产文件仅：

1. `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextFactory.kt`
   - 裁定前 SHA `2f5ab913e4eb549475697ba5b6b9ab2cf4fe374a5db239da51eba21f4d0b6fc8`。
2. `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/AlcyoneContextImpl.kt`
   - 裁定前 SHA `908ec89826cd905d1712cd226a5a09af8d38ab7ea509e895decb309caa9e99f4`。

原 R2 三个测试文件继续允许修改，其他生产/测试、Schema、B1 Server、构建、VFS/common、CT27 文件全部冻结。不得新增生产 helper 文件；计数包装器放在原 `AlcyoneContextReadyTest.kt` 内。

## 2. Factory 接缝

在 `AlcyoneContextFactory` 内定义仅模块可见的关闭能力，不修改 `vfs/api`：

```kotlin
internal interface ManagedVfs : Vfs {
    suspend fun close()
}
```

增加私有默认工厂：调用真实 `AlcyoneVfs.create(config)`，以 `Vfs by delegate` 委托所有业务方法，只显式把 `ManagedVfs.close()` 委托给 `AlcyoneVfs.close()`。不得在 `create()` 组装阶段调用该工厂。

现有 **internal** 测试重载末尾追加可选参数，保持已有位置参数兼容：

```kotlin
vfsFactory: suspend (VfsRuntimeConfig) -> ManagedVfs = ::defaultVfs,
```

private `build` 接收同一工厂并传给 Impl。公共 `create(contextConfig, vfsConfig)` 的签名、返回类型和调用方式完全不变，只向 private build 传 `::defaultVfs`；不得给公共入口增加 Vfs、工厂或测试探针参数。

## 3. Impl 不变边界

- 构造器保存上述 internal 工厂；`performStart` 只在原 `AlcyoneVfs.create(vfsConfig)` 的位置调用 `vfsFactory(vfsConfig)`，仍处于 executor 打开之后、LLM/Pipeline/Backup 组装之前。
- 原 `AlcyoneVfs?` 的 VFS 字段、`acquiredVfs`、`vfsToClose`、清理参数、Memory Map 参数与 `vfsForTesting()` 可统一改为 `AlcyoneContextFactory.ManagedVfs?`，不得改成无关闭能力的裸 `Vfs?`。
- 清理仍直接调用同一实例的 `close()`；VFS→executor 的相对顺序、ReleaseProbe 时点、NonCancellable、异常链、启动失败逆序清理及 RUNNING 发布逻辑均不得变化。
- `defaultMemoryMapPipeline`、`ContextResourceAccess`、`BackupRuntime` 继续收到同一实例；不得复制 VFS、另开 AssemblyProbe 或改变业务调用。
- `vfsForTesting()` 仍为 internal；Ready/Admin 生产方法不得读取计数器、识别测试包装或新增任何分支。

## 4. 测试证据要求

- Ready 测试通过新 internal 参数返回“委托真实 `AlcyoneVfs` 的 ManagedVfs 计数包装器”；实际 SQLite、Factory、Runtime.start 与 Ready 路径不变。
- 包装器对 `Vfs` 的**每个业务入口**计数，包括接口默认方法 `publishNew`/`safeReplace`，`close` 不计入业务调用；不能只覆盖当前猜测会被调用的方法。
- Map/Backup 等启动工作稳定后清零；仅在 `checkReady()` 区间断言 VFS 计数为 0，并同时断言受控 LLM HTTP 请求数不增加。Pending 非空、Map 非 CURRENT、LLM 历史失败/不可用业务状态不得使 Ready 降级。
- 原 R2-1、R2-3 和 Worker 聚合要求不变；不得借新工厂伪造 SQLite 结果或绕过真实 VFS 初始化/关闭。

## 5. 复验与停止条件

除原四模块 32 tasks、Spotless、source/XML totals 外，Lead 至少独立重跑现有 `AlcyoneContextLifecycleTest`、`AlcyoneContextCloseSharingTest`、`AlcyoneContextResourceIntegrationTest`、`AlcyoneContextVfsIntegrationTest`、Memory Map/Backup 相关 Runtime wiring，证明具体类型泛化没有改变启动与释放。公共 create 签名及其他保护 SHA 必须逐项核对。

如果该方案仍需修改 VFS 模块、公共 API、额外生产文件或改变关闭顺序，立即停止并重新提交 Architect；不得由 Worker 自行扩大范围。

**裁定：仅上述最小 internal 测试接缝 APPROVED；B2a-R2 可继续，最终通过与否仍待独立复核。**
