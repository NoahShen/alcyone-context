# CT18 B1-R1 · 重复 JSON key 修复 Lead 复验

## 本次修改（2026-11-08）

Lead 独立复验 B1-R1：核对最小修改范围、根对象重复 key 扫描、转义等价 key、三字段测试、3 模块定向、1756=1756、CT17 23 SHA、81 VFS 基线、Schema 与白名单边界；未修改生产/测试代码，未派发 Worker，未启动 CT19，无 Git 写操作。

**日期**：2026-11-08
**状态**：**B1-R1 Lead 复验通过；R1 CLOSED；建议提交 Architect 最终复核**
**依据**：[B1_Lead初验](B1_Lead初验.md)、[B1 Worker 交付](B1_Worker交付.md)、[B1 委派单](B1_委派单.md)、[CT18 任务书](CT18_任务书.md)
**基线**：B1 初始 45 用例；14 模块 **1755=1755**；Schema `ec727a53…8ea3`

## 1. 结论

- **新增 R 阻断：0**
- **R1：CLOSED**
- **P 建议：0**
- 根对象重复 key 检测在 `Json.parseToJsonElement` 前执行，三个允许字段与转义等价 key 均拒绝。
- 新增重复 key 测试真实执行；14 模块当前 **1756=1756**。
- 建议提交 Architect 最终复核；**Lead 不自标 B1/CT18 DONE**，CT19 不启动。

## 2. 最小修改范围

B1-R1 相对 B1 初始交付只修改：

- `context/runtime/src/main/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParser.kt`
- `context/runtime/src/test/kotlin/com/github/noahshen/alcyone/context/memory/runtime/map/JsonMemoryMapResponseParserTest.kt`
- `docs/context/tasks/cm4-ct18/B1_Worker交付.md`

其余 15 个 B1 白名单文件 SHA 与 B1 初始交付一致。B1 累计仍严格为 18 文件白名单：18 新增 / 0 修改既有文件；白名单外 B1 代码/测试变更 0。

Lead 独立复算 Worker 报告 40 个 SHA 行（17 个 B1 文件 + 23 个 CT17 保护文件）：**40/40 一致**。

## 3. 重复 key 扫描审查

### 3.1 执行顺序

通过：

- Parser 在任何 JSON tree 构建前先调用 `RootObjectKeyScanner(content).hasDuplicateKey()`。
- 重复 key 命中后立即抛 `LlmException(INVALID_RESPONSE)`，不会进入 `Json.parseToJsonElement`。
- 扫描失败/非法 JSON 交给原 JSON parser 严格拒绝，不修复围栏或前后文本。

### 3.2 根对象成员名解码

通过：

- 只扫描首个非空白字符为 `{` 的根对象。
- 成员名通过严格 JSON string token + `Json.parseToJsonElement` 解码，`over\u0076iew` 与 `overview` 得到同一 key。
- HashSet 检测重复；重复 `overview/navigation/domains` 均拒绝。
- 值通过字符串、composite 平衡或 primitive 跳过；嵌套对象只做括号/字符串平衡，不递归检查重复 key，符合 CT18 Schema 范围。

### 3.3 新增测试

`duplicate root keys are rejected before json object overwrite` 真实执行 4 个反例：

- 重复 `overview`。
- 重复 `navigation`。
- 重复 `domains`。
- `over\u0076iew` 与 `overview` 转义等价。

全部断言 `LlmException(INVALID_RESPONSE)`。`JsonMemoryMapResponseParserTest` 源码/XML 均 **8=8**，重复 key 用例实际进入 XML。

## 4. 回归与执行证据

### 4.1 3 模块定向

```text
./scripts/dev gradle :context:core:test :context:runtime:test :integration-tests:context:test --rerun-tasks --console=plain
```

- 首次沙箱执行因 Gradle FileLock `SocketException: Operation not permitted` 未启动测试。
- 经沙箱外批准重跑：**exit 0**，`BUILD SUCCESSFUL in 45s`，32 tasks executed。

### 4.2 计数

| 模块 | source | XML | fail | error | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| context-core | 110 | 110 | 0 | 0 | 0 |
| context-runtime | 515 | 515 | 0 | 0 | 0 |
| integration-context | 22 | 22 | 0 | 0 | 0 |

14 模块合计 **1756=1756**，全部 fail/error/skip 0。B1 初始 45 + R1 新增 1 = 46。

### 4.3 其他测试

- Generator 14、Publisher 6、E2E 1 继续通过。
- E2E 仍为真实 SQLite + LocalFs + Fake LLM，发布后 VFS 读回并严格解析。
- 未发现测试假执行或 safeReplace 绕过。

## 5. 边界证据

- CT17 23 个代码/测试文件 SHA：**23/23 一致**。
- 81 VFS 基线：**75 一致、6 个 CT17 B1 批准差异、缺失 0、未批准差异 0**。
- Context Schema SHA：`ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，未变。
- VFS/common/Schema/SQL/依赖/构建/API/Factory/Impl/Config/Loader/Persistence/Server：无 B1-R1 修改。
- `git diff --check`：exit 0，空输出。

## 6. 备注

### N1：全量与 Spotless 证据归属

Lead 独立复跑 3 个定向模块；Worker 已记录 Runtime Spotless 与全量 `./scripts/check` exit 0。本轮未重复全量/Spotless，不把 Worker 结果写成 Lead 实跑。

### N2：E2E internal 可见性风险保持

E2E 文件级 `INVISIBLE_REFERENCE` suppression 仍存在，编译和实跑通过；不改变生产可见性，B1-R1 未触碰该文件。该风险已在 B1 Lead 初验记录。

## 7. 是否提交 Architect

**是，建议提交 Architect 最终复核。**

- R1 CLOSED；
- 未发现新增 R/P；
- B1 仍为 **IN_REVIEW / 未 DONE**；
- CT18 未 DONE；
- CT19 不启动；真实模型 not-run；无 Git 写操作。
