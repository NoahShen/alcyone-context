# CT28 B1 Architect R1 补测委派

## 本次修改（2026-11-11，补测关闭与契约文字澄清）

- Architect 最终复核 **R1 CLOSED / B1 APPROVED / DONE**，四套件 32/32、Server 62/62、全量 132/132、14 模块 2188=2188；B2 另行按精确接缝清单推进。
- `TYPE_MISMATCH` 的最终固定 HTTP code 为 `RESOURCE_TYPE_MISMATCH`；本文件早先的 `INVALID_RESOURCE_TYPE` 为 Architect 文档笔误，不是生产或测试缺陷。

## 历史修改

依据 `Architect复核.md`，只补验收测试，不修改生产九文件、Schema、构建或其他模块。严格 Architect→Lead→Worker，Worker实现自测，Lead独立复验，Architect最终复核。

## 必补

1. Search真实Runtime无匹配返回HTTP 200且结果真正为`[]`；受控`ContextRetrievalException(UNAVAILABLE或CONFIGURATION)`检索失败映射503 `RETRIEVAL_UNAVAILABLE`，固定错误且不泄露异常文本，不得伪200空列表。
2. Search缺`query`、`query:null`、截断UTF-8及孤立`0xFF`均400 `INVALID_REQUEST`且SDK调用数不增加。
3. Resource meta/content首块提交前补齐`INVALID_URI→400 INVALID_URI`、`TYPE_MISMATCH→400 RESOURCE_TYPE_MISMATCH`（历史笔误已澄清）、`NOT_FOUND→404 RESOURCE_NOT_FOUND`、`UNAVAILABLE→503 RESOURCE_UNAVAILABLE`固定矩阵；保留OUT_OF_SCOPE 403及秘密零回显。

只改 `ReadHttpApiTest.kt`、`ReadHttpResourceTest.kt`、`ReadRuntimeFixture.kt`、必要时`ServerLogCapture.kt`。不得修改生产或降低既有断言。完成spotlessApply、四suite及`:context:server:check --rerun-tasks`，保存真实日志/XML并汇报精确计数；全仓check留待Architect R1通过后最终执行。禁止Paseo/MCP/Agent工具，无Git写操作。
