# CT28 B2a-R2 VFS 测试接缝窄修订

## 本次修改（2026-11-11，取消默认包装，保留既有 AlcyoneVfs 诊断）

- 四模块 823 例仅既有 Resource stream 回收测试失败：默认匿名 ManagedVfs 包装隐藏 `AlcyoneVfs.getTrackedStreamCount`；这是新接缝引入的诊断类型回归，不得修改既有测试或放宽验收。
- **不批准** Lead 建议的 wrapper delegate 解包与新增双 VFS 测试访问器。采用更小方案：internal 工厂直接返回公共 `Vfs`，生产默认直接返回 `AlcyoneVfs`；业务字段恢复 `AlcyoneVfs?`，清理通过 internal managed accessor 调用 close。
- 不增加公共 API、不反射、不跳过 close、不改变资源顺序；Factory/Impl 已在允许写集合内，R2 三测试文件继续，B2a 未 DONE，B2b/c 不放行。

## 1. 精确调整

Factory：

- 删除 `ManagedVfs` 接口与 `defaultVfs` 匿名包装。
- existing internal create 重载及 private build 的工厂类型改为：

```kotlin
vfsFactory: suspend (VfsRuntimeConfig) -> Vfs = AlcyoneVfs::create
```

- 公共 create 保持原签名，向 private build 传 `AlcyoneVfs::create`；仍在 start 的原位置调用工厂。

Impl：

- 构造器工厂类型改为 `suspend (VfsRuntimeConfig) -> Vfs`。
- 原 VFS 字段、`acquiredVfs`、`vfsToClose`、清理参数、Memory Map 参数及 `vfsForTesting()` 恢复 `AlcyoneVfs?`，保持本任务引入接缝前的既有类型和诊断行为。
- 增加 internal `managedVfsForTesting(): Vfs? = vfs`，仅供 internal 清理路径和必要的关闭 null 检查使用；生产公开契约不暴露它。
- `performStart` 在取得工厂结果后先赋给 `Vfs` 临时值，再按原类型赋给 acquired/field。生产默认必为 `AlcyoneVfs`；若 internal 测试工厂返回其他实现，使用 `ContextConfigException(INVALID_CONFIG)` 明确拒绝并走既有启动失败清理，不能静默跳过关闭。

清理：

- `releaseResources` 保留业务参数 `acquiredVfs: AlcyoneVfs?`；另接收同一当前实例的 `managedVfs: Vfs?` 仅用于 close。
- 仍在原 ReleaseProbe(VFS) 之后、executor 之前，对 `managedVfs` 分派：`AlcyoneVfs` 调原 `close()`；其他 `Vfs` 测试实现若实现 `AutoCloseable` 则调用其 `close()`，否则明确抛配置异常，不无声泄漏。
- R2 的 Counting 测试 VFS 同时实现 `AutoCloseable`，`close()` 幂等并委托真实 `AlcyoneVfs.close()`。不新增 `ManagedVfs` 生产接口。

## 2. 为什么优先此方案

- 默认生产路径不再有任何匿名委托，既有 `vfsForTesting(): AlcyoneVfs?`、Resource 反射诊断与 Ownership 测试原样工作。
- 测试重载只需返回 `Vfs`，计数包装器仍委托所有业务入口，不改变 Ready 生产逻辑。
- 不为测试解包生产 wrapper，不让生产反射识别 delegate，也不改变 public create 或 VFS 模块。

## 3. 关闭失败测试边界

既有 `LlmRuntimeLifecycleTest` 若需要从关闭角度断言字段释放，可在**不改既有测试**的前提下优先直接保留 `vfsForTesting()` 默认路径断言；`managedVfsForTesting()` 仅新增供新 Counting 测试或清理实现使用。若当前 Worker 已把既有测试断言改到 managed accessor，应恢复原既有测试文件；本批不允许修改它们。

## 4. 复验

必须重新运行失败的原 Resource stream 用例、Ownership/Lifecycle/CloseSharing/VfsIntegration 与 R2 Ready/Admin/Backup，再跑四模块全量及格式。Lead 与 Architect 均核对默认 `AlcyoneVfs::create` 无包装、所有 custom VFS 都有确定性 close、失败不吞异常。

若实现时发现必须改变既有生产关闭语义或修改额外测试，继续暂停上报；不得以编译便利扩大范围。
