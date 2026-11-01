# F2（非法 UTF-8 静默篡改正文）Lead 独立初验

## 本次修改（2026-10-29，全面复查 F2 初验，提交 Architect 复核）

- P4 已 APPROVED。F2 由同一 Paseo worker 配置 Worker `d7695b22` 单独批次实施（未合并其他修复）；Lead 未写生产代码。
- 范围：仅 `context/server` 与测试；未改 API/Core/Runtime/Persistence；无 git 写操作。

## 1. 交付物（SHA 已核对）

| 文件 | SHA-256（前缀） |
| --- | --- |
| `server/IngestionHttpApi.kt` | `7952a207729defd0` |
| `test/InvalidUtf8BodyTest.kt`（新增） | `e0c4fd7623ec5f23` |

## 2. Lead 独立实跑（非引用 Worker 日志）

| 模块 | 结果 |
| --- | --- |
| `context:server:test` | **24 例**（原 19 + 新增 5），0 失败/错误/跳过，源码=XML=24 |
| `context:core:test` | 11 例，0 失败 |
| `context:runtime:test` | 87 例，0 失败 |
| `context:persistence-sqldelight:test` | 167 例，0 失败 |
| `server spotlessCheck --rerun-tasks` | exit 0 |

日志 `.local/validation/cm1/lead/rereview-f2/check.log`。

## 3. 实现审查

- 顺序：`readBodyUpTo`（Content-Length + 流式，超限返回 null → 413）→ `decodeStrictUtf8`（`CharsetDecoder` + `CodingErrorAction.REPORT`，malformed/unmappable 均 REPORT）→ JSON 反序列化。
- `decodeStrictUtf8` 返回 null 时映射 **400 `INVALID_TURN`**，固定响应体；不再用会静默替换的 `String(bytes, UTF_8)`。
- 保持 strict JSON（未知字段 400）、错误映射（401/403/409/413/503/500）、脱敏与生命周期语义未变。

## 4. 测试核对（5 例，真实 testApplication + 真实 SQLite，字节级探针）

| 用例 | 关键断言（已核实） |
| --- | --- |
| `invalid utf8 bytes are rejected with 400 and never reach the database` | `0xFF`、孤立 `0x80`、截断 `E4 B8`、代理项 `ED A0 80` 全部 **400**、响应体固定 `{"error":"INVALID_TURN"}`、**库内 0 行** |
| `valid multibyte content including literal U+FFFD is accepted` | 中文 `中文记忆`、emoji `🚀🧠`、**合法 U+FFFD（EF BF BD）** 均 **201** 且落库 |
| `byte limit is enforced before utf8 validation` | 超 2 MiB 且全 `0xFF` → **413**（限额先命中，非 400），库内 0 行 |
| `instance small limit still rejects invalid utf8 within the limit as 400` | 实例 `maxTurnBytes=512`：限额内非法 → 400；超限非法 → 413 |
| `rejections do not leak exception details or the raw bytes` | 拒绝响应精确固定 JSON，不含异常类名/message、`jdbc`、Key、U+FFFD |

Architect 探针 `ServerBoundaryProbeTest.malformedUtf8IsNotSilentlyStored` → `MALFORMED_UTF8_STATUS=400`（Worker 报告；该探针中另一用例属 F3 范围）。

## 5. 结论与边界

- **F2 初验通过**：字节限额先于严格 UTF-8 解码；非法字节固定 400 且零行；合法中文/emoji/真实 U+FFFD 不误拒；限额、strict JSON、错误脱敏与既有用例保持。
- 提交 **Architect 独立复核**；通过后继续 **F3 → P2/P3 → F4**；全部闭环并整体回归后直接启动 CT08。**CM2 实现仍未启动**；未 git 提交/发布。
- 说明：`IngestionClientRegistry.of` 重复 runtimeId 静默覆盖为独立缺陷（F3），F2 未触碰，等下一批授权。
