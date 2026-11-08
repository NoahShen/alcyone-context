# CT17 Architect 裁定

**日期**：2026-11-08
**状态**：**APPROVED；CT17 范围与 B1 边界冻结；B1 委派单已准备，尚未派发 Worker**
**依据**：[CM4 范围与依赖方案](../../milestones/cm4/CM4_范围与依赖方案.md)、[CT17 方案核对](方案核对.md)、CT01 G3/G4、技术实现设计 §7.3/§7.7

## 1. 裁定效力

本文件为 CT17 的 Architect 最终裁定，覆盖此前方案中的待裁定项。CT17 按以下边界推进：

- 先完成 **B1 VFS safeReplace**；B1 未 DONE 不派发 B2。
- B2 才实现 Context Resource 访问。
- CT17 不实现 Map 生成、Bootstrap、Search/Get。
- 不新增依赖，不改 common/Schema，不接 Context 生产代码到 VFS safeReplace。
- 不运行真实模型，无 Git 写操作。

## 2. 冻结裁定 D1～D10

### D1：显式 VFS API

采用显式：

```kotlin
suspend fun safeReplace(
    uri: VfsUri,
    content: ByteArray,
): NodeInfo
```

**不扩展通用 `WriteMode`**。`safeReplace` 表示目标文件不存在则创建、存在则完整替换；不得与普通 `write` 的覆盖语义混淆。

### D2：公共能力与兼容性

- 允许新增 VFS 公共能力，但仅限 CT17 批准白名单。
- `Vfs.safeReplace` 可为第三方/默认实现提供默认 `UNSUPPORTED_OPERATION`，避免未实现能力被伪装成功。
- **不承诺 JVM ABI 不变**；本轮只要求源码兼容和明确能力探测。

### D3：LocalFs 实现

LocalFs 必须：

1. 在目标同 Mount、同目录创建唯一临时文件；
2. 完整写入并确认临时文件；
3. 使用经真实 LocalFs 探针验证的原子 replace；
4. 不得用未验证的 OpenDAL rename 冒充原子替换；
5. 不得先删除旧目标，不得退化为普通覆盖。

必须包含真实 LocalFs 探针和失败注入；替身只能证明编排，不能替代真实原子性证据。

### D4：WebDAV

- WebDAV 首版不支持 Map `safeReplace`。
- `WebDavStorage.safeReplace` 必须返回 `UNSUPPORTED_OPERATION`，能力位不得虚报。
- 不得降级为删除后 MOVE、普通覆盖或复制。
- 未来若用户要求 WebDAV Map，另行扩大 CT17 范围并验证具体服务端语义。

### D5：失败保旧语义

- **原子提交前失败**：旧文件必须逐字节不变。
- **物理原子替换成功、VFS Node/Event 状态提交失败**：文件内容为完整新版，返回 `STATE_ERROR`，`effect=PARTIAL`；不得伪造旧版仍在。
- CT17 验收严格按该口径，不要求也不允许声称跨 SQLite/VFS 的联合原子回滚。

### D6：safeReplace 文件语义

- 允许目标文件不存在时首建。
- 允许补目标 Mount 内缺失父目录。
- 目标为目录时拒绝。
- 未注册的物理文件允许替换并在成功后登记。
- 已注册文件替换保留原 Node ID、Metadata、registeredAt，只推进 updatedAt。
- 首次创建事件为 `FILE_CREATED`；替换已有文件事件为 `FILE_WRITTEN`。
- 临时文件不得登记 Node、不得发独立事件。

### D7：Resource 访问

Resource 只允许：

- 严格 `VfsUri.parse`；
- 首段恰为 `resources`；
- 至少一个非空资源路径段；
- 文件，不含目录、`memory`、其他 namespace、逻辑根。

`ResourceMetadata` 最小字段冻结为：

```kotlin
data class ResourceMetadata(
    val uri: String,
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
)
```

新增 `ContextResourceException` 及安全错误码；不得把 VFS/OpenDAL 异常类型暴露为公共契约。

`read` 复用 VFS 默认 16 MiB 可配置上限。`readStream` 为冷 Flow、64 KiB 分块、每次 collect 独立开/关流；完成、异常、取消、提前结束均关流。不承诺打断正在阻塞的 native read。

### D8：缺失 resources mount

缺失 `resources` namespace/mount 时，Resource 调用返回具名失败；**不在 create/start 强制失败**，保持 CM1 最小配置兼容。

### D9：loadMeta 副作用

- `loadMeta` 允许触发 VFS 内部 Node 懒注册。
- 不得发文件变更事件，不得写 Resource 内容。
- 公共语义仍是只读 Resource 访问；不得宣称 VFS 状态库零写入。

### D10：分批与放行

CT17 固定拆为：

1. **B1**：VFS safeReplace；
2. **B2**：Context Resource 访问。

B1 未 DONE 不派 B2；B1/B2 均需 Lead 初验后交 Architect 复核。

## 3. B1 派生要求

- 生产白名单只含 `vfs/api`、`vfs/core`、`vfs/storage`、`vfs/runtime` 的必要文件。
- 测试必须覆盖真实 LocalFs、Core Node/Event、Runtime 生命周期、WebDAV 拒绝和失败注入。
- Context API/Core/Runtime/Persistence、common、Schema、依赖、构建文件均不得修改。
- 81 VFS 基线不再要求零差异；只允许批准白名单文件出现预期差异，其他基线文件必须一致。

## 4. 当前状态

- Architect 裁定：**APPROVED**。
- B1 委派单：**READY，未派发**。
- B2：**LOCKED，待 B1 DONE**。
- CT17：**IN_PROGRESS / 方案冻结，未实现**。
- 真实模型：**not-run**。
