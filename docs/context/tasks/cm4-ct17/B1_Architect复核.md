# CT17 B1 · Architect 复核

**日期**：2026-11-08
**状态**：**B1 APPROVED / DONE；P1 接受为残余风险，不做 B1-R1；B2 解锁**
**复核对象**：[B1 Worker 交付](B1_Worker交付.md)、[B1 Lead 初验](B1_Lead初验.md)、B1 实际工作区 Diff
**依据**：[Architect 裁定](Architect_裁定.md)、[CT17 任务书](CT17_任务书.md)、[B1 委派单](B1_委派单.md)

## 1. 最终结论

**CT17 B1 APPROVED / DONE。**

- R 阻断：0。
- 文件范围、生产实现、测试矩阵、真实 LocalFs 原子性、WebDAV 拒绝、全量回归和工程边界均通过。
- B1 的 29 个新增用例、14 模块 **1682=1682**、定向测试与 `./scripts/check` 均通过。
- B2 已解锁，可准备/派发独立 Worker；仍不得实现 Map/Bootstrap/Search/Get。
- CT17 整体仍为 **IN_PROGRESS**，不得标记 DONE。

## 2. 复核证据

| 项目 | 结论 |
| --- | --- |
| 文件白名单 | 14 文件：9 修改 / 5 新增；白名单外 B1 变更 0 |
| 新增测试 | 29 个，A01～A06/A08 映射齐全 |
| 定向测试 | 5 个 VFS 模块通过，26 tasks executed |
| 全量回归 | `./scripts/check` exit 0，131/131 tasks |
| 14 模块计数 | 源码 `@Test` = XML = **1682**，fail/error/skip 全 0 |
| 真实 WebDAV | `./scripts/webdav-check` 43=43，0/0/0 |
| 真实 LocalFs | 原子探针、首建/替换、旧版保旧、状态失败 PARTIAL 通过 |
| 81 基线 | 75 一致、6 项均为批准 VFS 生产文件差异、未批准差异 0 |
| Schema | SHA `ec727a53…8ea3` 未变 |
| 依赖/构建/common/Context | B1 零修改 |
| Git | `git diff --check` 空；无 git 写操作 |

## 3. 生产契约复核

- `Vfs.safeReplace(VfsUri, ByteArray): NodeInfo` 为显式公共 API，默认实现返回 `UNSUPPORTED_OPERATION`。
- `StorageCapabilities.atomicReplace` 默认 false；`Storage.safeReplace` 默认拒绝。
- `DefaultVfs` 在单状态锁内完成能力/路由/只读/限额/父目录/Storage/Node/Event 编排。
- LocalFs 使用同目录临时文件、完整写入与长度确认、真实原子 replace；无 delete+move、普通覆盖或 OpenDAL rename 降级。
- WebDAV 能力 false，直接拒绝且零网络副作用。
- 物理替换成功、Node/Event 提交失败时，完整新版保留并返回 `STATE_ERROR + PARTIAL`。
- `AlcyoneVfs.safeReplace` 进入既有在途操作/关闭/取消协议。

## 4. P1 裁定

Lead 提出的 P1：清理失败后的残留临时文件可能在后续 `list` 中重新可见。

**Architect 裁定：接受为 B1 残余风险，不做 B1-R1。**

理由：

- 仅在临时文件清理失败这一极端路径发生；
- 不影响旧文件保旧、目标文件完整性、Node/Event 或错误 `effect`；
- 严格跨重启隐藏需要新增内部前缀保留/统一过滤契约，超出已批准 B1 范围；
- 后续若用户或产品要求临时文件严格不可见，另立任务处理。

该 P1 不阻断 B1 DONE，也不阻塞 B2。

## 5. B2 放行边界

B2 只允许实现：

- `loadMeta(uri)`；
- `read(uri)`；
- `readStream(uri): Flow<ByteArray>`；
- `ResourceMetadata(uri, sizeBytes, modifiedAt)`；
- `ContextResourceException` 安全错误码；
- `/resources/<non-empty>` URI 范围；
- 16 MiB VFS ByteArray 限额复用；
- 冷 Flow、64 KiB 分块、每次 collect 独立开/关流；
- 缺失 `resources` mount 时调用时具名失败。

B2 不得：

- 修改 VFS 生产代码；
- 修改 Schema/common/依赖/构建/Prompt；
- 实现 Map/Bootstrap/Search/Get；
- 自标 CT17 DONE；
- 跳过 Lead 初验与 Architect 复核。

## 6. 当前状态

- B1：**APPROVED / DONE**。
- P1：**ACCEPTED RISK，无 B1-R1**。
- B2：**READY，可派发；当前未派发**。
- CT17：**IN_PROGRESS**。
- CT18：**未启动**。
- 真实模型：**not-run**。
- 无 Git 写操作。
