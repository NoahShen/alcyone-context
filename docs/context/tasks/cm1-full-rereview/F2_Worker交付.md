# CM1 全面复查 F2 Worker 交付（非法 UTF-8 静默篡改正文）

**任务**：CM1 全面复查 F2：HTTP 请求体非法 UTF-8 必须 400，不能被静默替换为 U+FFFD 后入库
**Worker**：Paseo worker 配置（pi / my_magpie/group/worker，thinking high）
**Lead**：6f547b7e-4ced-4f1b-92a7-e665b2d008db
**状态**：Worker 自测通过，待 Lead 初验与 Architect 独立复核；**不得自标 DONE**
**日期**：2026-10-29

## 本次修改（2026-10-29，F2 严格 UTF-8 解码）

- `IngestionHttpApi` 在限额检查之后、JSON 反序列化之前，新增 `decodeStrictUtf8`：用 `CharsetDecoder` + `CodingErrorAction.REPORT` 严格解码，非法字节 / 截断序列 / 孤立代理项 → `400 INVALID_TURN`（固定脱敏响应体）。
- 顺序保持：`Content-Length` → 流式 `readBodyUpTo` 限额（413）→ 严格 UTF-8（400）→ strict JSON（400）。
- 新增 `InvalidUtf8BodyTest` 5 例（真实 `testApplication` + 真实 SQLite，字节级探针）。
- 未改 API/Core/Runtime/Persistence；未与其他修复合并；未 `git add/commit`。

## 1. 实现

```kotlin
val body = readBodyUpTo(call.receiveChannel(), maxTurnBytes)
    ?: return@post call.respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")

// 限额之后、反序列化之前：严格 UTF-8，非法字节一律 400
val text = decodeStrictUtf8(body)
    ?: return@post call.respondError(HttpStatusCode.BadRequest, "INVALID_TURN")
```

```kotlin
private fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        null
    }
```

- 不用 `String(bytes, Charsets.UTF_8)`（会静默替换成 U+FFFD，等于篡改正文）。
- 合法 U+FFFD（`EF BF BD`）是正常字符，不受影响。
- 响应体固定 `{"error":"INVALID_TURN"}`，不含异常类名、message、Key、原始字节或连接串。

## 2. 测试（新增 5 例，字节级探针）

| 用例 | 断言 |
| --- | --- |
| `invalid utf8 bytes are rejected with 400 and never reach the database` | 在 JSON `content` 中分别注入 `0xFF`、孤立 `0x80`、截断 3 字节序列（`E4 B8`）、以 CESU 编码的代理项（`ED A0 80`）→ 全部 400、响应体固定、**库内 0 行** |
| `valid multibyte content including literal U+FFFD is accepted` | 中文、emoji、合法 U+FFFD 字符均 201，库内 3 行（证明不误拒） |
| `byte limit is enforced before utf8 validation` | 超过 2 MiB 且全是非法字节 → 413（限额先于解码），库内 0 行 |
| `instance small limit still rejects invalid utf8 within the limit as 400` | 实例 `maxTurnBytes=512`：限额内非法字节 → 400；超 512 非法字节 → 413 |
| `rejections do not leak exception details or the raw bytes` | 响应精确为固定 JSON，不含 `Exception`/`jdbc`/Key/U+FFFD |

既有 `IngestionHttpApiTest`（8）、`IngestionHttpErrorMappingTest`（3）、`ServerConfigLoaderTest`（6）、`ServerSkeletonTest`（2）全部保持绿：strict JSON 未知字段 400、无 offset 400、认证 401/403、幂等 201/200、冲突 409、生命周期 503 语义不变。

## 3. 命令、退码与计数

| 命令 | 退码 | 结果 / 日志 |
| --- | --- | --- |
| `:context:server:spotlessApply :context:server:compileKotlin` | 0 | 生产编译 |
| `:context:server:spotlessApply` | 0 | 格式化；`spotless-apply.log` |
| `:context:server:test :context:server:spotlessCheck --rerun-tasks` | 0 | **24 例，0 失败 / 0 错误 / 0 跳过**；源码 `@Test` 24 = XML 24，**差额 0**；`targeted-test.log` |
| `:context:server:test :context:server:spotlessCheck :context:core:test :context:runtime:test :context:persistence-sqldelight:test --rerun-tasks` | 0 | 全绿：server 24 / core 11 / runtime 87 / persistence 167，源码 `@Test` 与 XML 全一致 |

server 24 = 原 19 + `InvalidUtf8BodyTest` 5。

## 4. Architect 探针

`ServerBoundaryProbeTest.malformedUtf8IsNotSilentlyStored` 修后：

```
MALFORMED_UTF8_STATUS=400
```

> 同一探针文件的第二个用例 `duplicateRuntimeRegistrationMustNotSilentlyDiscardCredentials` 仍失败：`IngestionClientRegistry.of(...)` 对重复 runtimeId 采用 `associateBy`，会静默丢弃其中一个注册。这是独立缺陷、**不属于 F2 范围**，此处未修，已向 Lead 报告，等待单独授权。

## 5. 改动文件与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `.../server/IngestionHttpApi.kt` | `7952a207729defd0910fe1ee9b12ec6fd1868230b0488e1e5655531d12b81efc` |
| `.../test/InvalidUtf8BodyTest.kt`（新增） | `e0c4fd7623ec5f23d2cec539085effed173a18633d65dc4280f418131c5ffd2c` |

## 6. 未做项与边界

- 不改 API/Core/Runtime/Persistence；不改 CT04/CT05/CT06 语义；未与其他修复合并。
- 严格 UTF-8 校验只作用于 HTTP 请求体；SDK 入参是 Kotlin 字符串，不存在原始字节层。
- 重复 runtime 注册的静默丢弃问题（见上）不在本批范围。
- 本文件仅为 Worker 自测交付，**不自标 DONE**；须 Lead 初验与 Architect 独立复核。
