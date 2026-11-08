# CM4 · Architect Milestone Review

**日期**：2026-11-09
**状态**：**CM4 APPROVED / DONE**
**复核对象**：[CM4 收口报告 Lead 终稿](cm4-ct21/CM4_收口报告.md)、CT17～CT21 各 Architect 复核
**范围**：CT17（VFS safeReplace + Context Resource）、CT18（Memory Map Snapshot/Generate/Publish）、CT19（Map 生命周期/Bootstrap/Wakeup）、CT20（Search/Get/Retrieval）、CT21（CM4 全景集成/评测/证据）

## 0. 本次修改

Architect 对 CM4 做 milestone review：独立复跑全量、复核 CT17～CT21 五任务全部 APPROVED 状态与终态 SHA、确认 VFS 工作区差异仅为 CT17 已批准的 12 文件、核对收口报告终稿与真实边界。结论 **CM4 APPROVED / DONE**；CT22/CM5 未启动授权。

## 1. 任务级状态确认（全部经 Architect 独立复核）

| 任务 | 状态 | 关键证据 |
| --- | --- | --- |
| CT17 | APPROVED / DONE | VFS safeReplace（LocalFs/WebDAV 原子替换）+ Context Resource（loadMeta/read/readStream、取消/关闭释放） |
| CT18 | APPROVED / DONE | Map 快照生成/校验/安全发布；14 模块 1756=1756 终态 |
| CT19 | APPROVED / DONE | Bootstrap 缓存读取/Factory 接线/Organization wakeup/E2E；B2 1819=1819 |
| CT20 | APPROVED / DONE | B1 纯检索链（48 例）+ B2 公共 Search/Get/GetRaw/配置/接线/E2E（15 例）；1882=1882 |
| CT21 | APPROVED / DONE | 全景 SDK 链/Map 交错/Search→Resource 取消/固定集 10+10；零生产修改；1885=1885 |

## 2. Milestone 独立验证

| 项 | 结果 |
| --- | --- |
| 全量 `./scripts/check` | exit 0（2m18s，131/131 tasks executed） |
| 14 模块 source `@Test` = XML | **1885=1885**；fail/error/skip 0/0/0 |
| Context Schema | `ec727a532654be574941e4902e307b750e38fb46b51df1ebd8b18ac1aadd8ea3`，CM4 全程未变 |
| VFS 工作区差异 | 恰为 CT17 B1 已批准的 12 文件（git status 与 CT17 B1 Worker 交付白名单逐项比对；SHA 12/12 复算一致）；无额外未批准差异 |
| common | diff 空 |
| 五任务收口文档 | CT17/CT18 收口报告、CT19/CT20 B2 Architect 复核、CT21 B1 Architect 复核均存在且结论一致 |

## 3. CM4 能力结论

CM4 已形成完整 SDK 逻辑闭环并具备真实栈证据：

- **数据闭环**：Conversation → Extraction → Organization（Raw/Organized/Source/watermark）→ Memory Map（safeReplace 发布）→ Bootstrap → Search（Scanner/Rerank）→ Get/GetRaw（权威回读）→ Resource（Meta/Read/Stream）。
- **一致性证据**：Map 发布/读取交错无半写、stale 收敛 CURRENT、权威数据以 SQLite 为准（模型不能伪造 ID/正文）。
- **生命周期证据**：请求序列确定（Extraction→Governance→Map→Scanner→Rerank）、close 后 0 新请求、stream 取消/关闭归零、业务入口 CLOSED。
- **评测基线**：cm4-v1 固定集 10+10、12 Prompt/Schema SHA、Fake 协议/结构结果、真实模型 not-run。

## 4. 观察与遗留（不阻断 DONE）

- N1（Journey 100ms 有界静默窗口）、N2（真实模型语义质量 not-run）、CT19 N1（internal accessor 命名债务）、CT20 N1（Prompt 标签转义、与 CT13 家族一致）：均保持记录性观察。
- 未实现项（按收口报告 §8）：HTTP 权限/路由/部署、FTS/Embedding/向量库、Dreaming、Backup、进程重启/WAL 恢复（D16 留 M6）、真实模型语义评测。
- 真实模型评测与后续里程碑（CT22/CM5/M6）需用户单独授权，不在本裁定内自动启动。

## 5. 结论

**CM4（CT17～CT21）APPROVED / DONE。**

- 五任务全部 Architect 复核通过；14 模块 **1885=1885**；`./scripts/check` exit 0；Schema 未变；VFS 差异仅 CT17 已批准集。
- VFS 基线 75+6 的解释规则保持：6 个差异即 CT17 B1 已批准的 safeReplace 变更，作为后续里程碑 VFS 基线的既成事实。
- 真实模型 not-run；无 Git 写操作；未提交工作区保持完整。
- 下一步选项（待用户决定，不自动启动）：真实模型评测授权、CT22/CM5 规划、或 M6 收口准备。
