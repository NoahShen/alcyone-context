# CT31 B2 Lead 初验

## 本次修改（2026-11-12，IN_REVIEW，最终修复与全量复验）

- 独立修复并复跑 evaluation **17/17**、Context 集成 **49/49**、全仓 `./scripts/check` **137/137**；14 模块 **314 XML / 2341 tests / 0 failures / 0 errors / 0 skipped**。
- 最终修复纠正 Governance NOOP/动作失真、Search STRICT Domain、Map ID/Domain 导航和 Dreaming DATA_ONLY/正则完整性；数据、manifest 与离线报告已同步。
- **建议提交 Architect 独立复核，不自行签 DONE。** B3 真实模型未授权/未运行，CT32 LOCKED，CM6 未 DONE；无 Git 写操作。

## 1. 实际执行证据

| 验证 | 命令/证据 | 结果 |
| --- | --- | --- |
| 格式与目标套件 | `./scripts/dev gradle spotlessApply :integration-tests:context:test --tests 'com.github.noahshen.alcyone.context.memory.integration.evaluation.*' --console=plain` | BUILD SUCCESSFUL；17/17，0/0/0 |
| 显式报告再生成 | `OfflineEvaluationReportTest --write-offline-report`（项目 JDK、既有 test/runtime classpath） | 写入 `evals/results/offline-v1.json`；普通测试比较重建值相等 |
| 全仓 | `./scripts/check` | 最终修复后 BUILD SUCCESSFUL，137/137，4m18s，日志 `/tmp/ct31-full-check-final.log` |
| 全量 XML | 14 模块 build/test-results/test | 314 XML / 2341 tests / 0 failures / 0 errors / 0 skipped |

XML 分布：common 23、context/api 45、core 180、persistence 192、runtime 823、server 108、integration-context 49、integration-vfs 125、vfs/api 67、core 343、git-backup 73、persistence 62、runtime 67、storage 184。

## 2. 写集合与 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `evals/README.md` | `f5f781b912f9c136ec3c59f3dc353f57130df597416c7e47d4df436516feb514` |
| `evals/manifest-v1.json` | `6a5c31224bb7ac42b82cf908e823176fcdf0efebae35a61678b8069fdc81efdc` |
| `evals/schema/dataset-v1.schema.json` | `bd6cb60e8464e7addb25bdc99a12af59b10fdc8d79f5596c36d96ef51588d7ae` |
| `evals/schema/report-v1.schema.json` | `561ffdd4db62a2a99d6444c872de7ceee044eff3e5abaaeb4f0abf9e073da994` |
| `evals/datasets/extraction.jsonl` | `205a5e410f1bf13ef9aa08a65256e56cd8cecde047033e9b938aa199a5d62a93` |
| `evals/datasets/scanner.jsonl` | `5569797c30f8be9237c5aff752b1e619148d169a53da6368b27159ab600a2f15` |
| `evals/datasets/governance.jsonl` | `484d21838ce965744d5ae168ec7e3788121bf1e1fa7c7bea775fc4ed830591f3` |
| `evals/datasets/retrieval.jsonl` | `ad714220180ccf6813d72fb5bfd85dcd3950c86f7200edf9830e8689368ea2d9` |
| `evals/datasets/memory-map.jsonl` | `de4c222cf477d95e18a0c6e67da57159b90cced9d196befd8fb621bd480278a4` |
| `evals/datasets/dreaming.jsonl` | `fdb67ebd605d7f398e41bd25345168d19b2c1330d5a6b4be56850116e6793eee` |
| `evals/results/offline-v1.json` | `eccf0eb2a25a605fa824f2aabf9a0c064e22d29f75e3bc6406869fc73e33d0b4` |
| `evals/results/real-model-not-run-v1.json` | `fa9ff233af1db23b4122a20752b6708cef51df6b2b07ba3f730a7a98fd7cd553` |
| `.../evaluation/EvalDatasetLoader.kt` | `f992e90cf860bf782ef53a801b2287567eca9773b343ca441e48fc769c64b528` |
| `.../evaluation/EvaluationDatasetIntegrityTest.kt` | `9eb40a20f23c3a3e4cead935f12867b36df4d3a62a0bbb90c8a7a878c1c69456` |
| `.../evaluation/OfflineEvaluationHarness.kt` | `33bf9afe63663810658e13f3cb07cdea8a04a16f7f2e17be04cc62407244c2f3` |
| `.../evaluation/OfflineEvaluationReportTest.kt` | `6b0afb2db2532e671f67e6b9eba8185a74dd378cd1cdc48576d18c06a4c22cb4` |

测试文件路径前缀均为 `integration-tests/context/src/test/kotlin/com/github/noahshen/alcyone/context/memory/integration/`。曾临时生成的调试/辅助文件均已删除或合并，最终不存在额外 evaluation 源码文件；不新增脚本、Gradle task 或依赖。

## 3. D1～D10 初验矩阵

- **D1～D3**：canonical 独立版本、旧文件只读、28 条来源链、Turn ID 规范化；Search/Map/Dreaming 人工 oracle 已逐条审查。
- **D4**：只交付上述 16 路径与任务文档；未改生产、旧测试、业务 Schema/SQL、构建、默认 Runtime。
- **D5**：H1/H2/H3 是独立标签/未来人工 criticalErrors，报告不以聚合分抵消；本次没有模型输出，因此 `criticalErrors=[]` 不表示无真实模型错误。
- **D6**：真实模型 not-run；B2 完成不等于 CT31 DONE，CT32 未放行。
- **D7**：Scanner/Rerank 独立输入和指标；首位相关、第二位相关、K/K+1、空 qrels 和非法 ID 均有公式反例。
- **D8**：测试 harness 无网络/密钥/LLM；非法/重复 ID 为 protocol failure；人工构造输出未混入 72 条 canonical。
- **D9**：manifest/data/Prompt/Schema SHA 可复算；报告无时间戳、随机值或本机路径，两次生成及 checked-in 值一致。
- **D10**：严格未知字段/JSONL/引用/Profile/route/合成与隐私标记校验，schema 反例和 28 条 provenance 对照有测试。

## 4. 保留事项

12 条/类是最小审计地板，不代表分布覆盖或质量门槛。B2 尚无真实模型输出、真实模型 H1/H2/H3 结论、Usage/成本或 Provider 验证；B3 未获授权，故 CT31 不能 DONE。CT30 的断电、Linux/WebDAV durable、默认恢复与生产部署 not-run 不变。
