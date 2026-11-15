# CT28 B1 Architect R1 定点委派单

## 本次修改（2026-11-11，Architect 复核后的测试补证）

- 固化 Architect message `d1af9804-7a4b-44ab-863e-eb5c5dcbe181` 的 R1：只补 A02/A03 验收覆盖，**不修改任何生产文件**。
- 严格按 Architect → Lead → Worker 执行；Worker 只修改允许的测试/测试辅助并自测，Lead 独立初验，Architect 再复核后才可放行 B2。
- 保留现有真实 Runtime/Local FS 测试与全部 CT27 工作区改动，不重写已工作的实现，不扩大测试范围。

## 1. 精确写集合

仅允许修改/新增：

- `context/server/src/test/kotlin/com/github/noahshen/alcyone/context/memory/server/ReadHttpApiTest.kt`
- `context/server/src/test/kotlin/com/github/noahshen/alcyone/context/memory/server/ReadHttpResourceTest.kt`
- `context/server/src/test/kotlin/com/github/noahshen/alcyone/context/memory/server/ReadRuntimeFixture.kt`（仅为真实无匹配 Search 的 controlled model 分流所需）
- `context/server/src/test/kotlin/com/github/noahshen/alcyone/context/memory/server/ServerLogCapture.kt`（仅确有必要；预期不改）

禁止修改：

- `context/server/src/main/**` 的生产 9 文件；
- 其他测试文件、其他模块、公共 API、Core/Runtime/Persistence 生产代码；
- Schema/Migration、Gradle/依赖、构建脚本、既有 CT27 Backup；
- Git 写操作与任何部署。

## 2. 必补验收

### A02 Search 无匹配与依赖故障

在 `ReadHttpApiTest.kt` 增加正式测试：

1. 使用真实 `ReadRuntimeFixture`（真实 Runtime + 文件 SQLite + controlled HTTP model），搜索明确的 `no-match` query；controlled model 按该 query 分流返回空 `candidate_ids` 与空 `memory_ids`，断言 HTTP 200 且响应 JSON 精确为空列表 `[]`。不能只用 Fake 返回 emptyList。
2. 使用受控 Spy/Fake 抛出 `ContextRetrievalException(ContextRetrievalErrorCode.UNAVAILABLE, secretMessage)`，断言 Search 为 503，固定 body 含 `RETRIEVAL_UNAVAILABLE`，不含秘密 message，且不得伪 200 空列表。

`ReadRuntimeFixture` 的最小调整应读取受控模型 HTTP 请求中的 `query`，只对 `no-match` 分流为空 ID；保留现有 `organized` 两阶段返回和 modelCalls 语义。若读取/解析请求体失败应让测试失败，不能默认空结果掩盖真实路径。

### A03 Search 显式非法输入

在 `ReadHttpApiTest.kt` 增加/扩展正式测试，逐项断言：

- 缺 `query`（如 `{}`）→ 400，精确 `{"error":"INVALID_REQUEST"}`，SDK `searchCalls` 不变；
- `"query":null` → 同上；
- 原始非法 UTF-8 字节至少覆盖截断多字节序列与 lone `0xFF` → 同上。

非法 UTF-8 必须用 ByteArray 原始请求，不得经 Kotlin String 预先替换为 U+FFFD。每项独立记录 before count 并核对不变，避免累计计数掩盖误调用。

### A02 Resource 固定错误矩阵

在 `ReadHttpResourceTest.kt` 或 `ReadHttpApiTest.kt` 增加正式测试，对 `INVALID_URI`、`TYPE_MISMATCH`、`NOT_FOUND`、`UNAVAILABLE` 至少覆盖：

| ContextResourceErrorCode | meta | content 首块/打开失败（提交前） |
| --- | --- | --- |
| INVALID_URI | 400 INVALID_URI | 400 INVALID_URI |
| TYPE_MISMATCH | 400 RESOURCE_TYPE_MISMATCH | 400 RESOURCE_TYPE_MISMATCH |
| NOT_FOUND | 404 RESOURCE_NOT_FOUND | 404 RESOURCE_NOT_FOUND |
| UNAVAILABLE | 503 RESOURCE_UNAVAILABLE | 503 RESOURCE_UNAVAILABLE |

- meta 通过受控 `loadMeta` 异常触发；content 必须通过 `readStream` Flow 在首块前异常触发，不只测 meta 后推断 content。
- 每项断言 HTTP status、固定错误 code、异常秘密 message 零回显；content 每项确认响应提交前未打开成功流/未伪 200。
- 保留原 OUT_OF_SCOPE、LIMIT_EXCEEDED 与提交后中断用例，不删改语义。

## 3. 生产保护与基线

以下 9 个 SHA-256 在 Worker 交付时必须逐项一致：

```text
f56a07d1b8ecd8fbda0bb6c930315e97fb8907887a2e5ca575a63274d7ced3c6  AlcyoneHttpServer.kt
99776df10edb4b2771511ca82eac9e3c1bd0f14ee9a5f4d135dd253e11d725df  HttpApiSupport.kt
b3d79782f9a653b04fa01f98484ee5b663b1c2f40fd52c2d388c5cd629b3ab0a  IngestionClientValidation.kt
aaead10b7caa0c6bdc71714c855dba88b0e89d8f151dc14bf060eb6ea8628024  IngestionHttpApi.kt
4a6a4df9e6532dd42f1e0872ae6e3caa4afa9d4c4f8b0a8a8c313cea254c5c3a  ReadHttpApi.kt
443e95448f3c4e0bae567c2aa9c5a15845f0d6d5c626a5ae73d78ea93029daaf  ReadHttpDto.kt
6daf773b937e0ce7b18248c8afb83c3b0bee419e4eb92d865700df50676422dd  ServerAuth.kt
f73c839c01c7f05211c026793f485b73f05befcfedc20e226ea38f87df5959fc  ServerConfig.kt
a90dd57d2f4e2836015f107e04ef066d69abfd1fa25066acbfb28d94a836b6e6  ServerConfigLoader.kt
```

## 4. Worker 验证与交付

执行：

```bash
./scripts/dev gradle spotlessApply
./scripts/dev gradle :context:server:test --rerun-tasks \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ReadHttpApiTest' \
  --tests 'com.github.noahshen.alcyone.context.memory.server.ReadHttpResourceTest' \
  --console=plain
./scripts/dev gradle :context:server:check --rerun-tasks --console=plain
```

最后完整 `check` 必须实际执行，保存最终 XML，不能被定向测试覆盖后冒充全套结果。交付：

- 精确修改文件与每个新增测试名/断言位置；
- 命令、退出码、XML tests/failures/errors/skipped；
- 生产 9 文件 SHA 未变核对；
- 未验证项；不得修改生产或宣称 B1/CT28 DONE。

Lead 后续独立复跑定向与 Server check，更新 `B1_Lead初验.md` 并交 Architect；R1 后最终全量由 Architect 按其裁定执行。
