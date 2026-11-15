# CT31 B3 R2 Lead 交接

## 本次修改（2026-11-14，最小实现裁定后恢复现有 Lead）

- 已新增 [最小实现裁定](B3_R2_最小实现裁定.md)，冻结六组评测修复的精确边界；不再等待 advisor，也不扩大为生产语义修复。
- 现有唯一 Lead 仍为 `912ba7c6-7ef4-4c63-b554-72d15ed3005e`，恢复实现消息已排队（messageId `63f29744-8975-401c-8e6b-b335aa61dfb0`）；Lead 须组织唯一 Worker，旧 Worker 不可继续时才替换并回报实际 ID/投递状态。
- 当前只允许离线实现、定向测试和 Lead 初验；ext-001 smoke 与 72 条须待 Architect 复核。B3 R1 OPEN，CT31/CM6 未 DONE，CT32 LOCKED，无 Git 写操作。

## 历史修改（2026-11-14，按活动记录更正实际进度）

- 原 Lead `cb727471-8e97-41c3-866a-898fb7f4bb62` 两次 R2 投递均结束/取消，无代码或验收证据。
- 新 Lead `912ba7c6-7ef4-4c63-b554-72d15ed3005e` 实际创建过 Worker `d9f37189-8403-444c-b152-2986cfd4845b`；活动记录证明其只读检查后被取消，未放行编码。先前仅凭文件时间写成“没有 Worker”不准确，特此更正。
- Lead 还创建了只读 advisor `16b9aa72-cfdd-420d-ac01-c76f5325575f`，用于审查评测可信度接缝；不得把 advisor 活动记为源码交付。当前不新建/恢复 Agent，等待其独立意见。
- 首轮 `.local/ct31/production-full-72` 保持冻结，B3 R1 OPEN；CT31/CM6 未 DONE，CT32 LOCKED，无 Git 写操作。

## 不变边界

1. 先修失败/Judge 原始证据、Dreaming/Map 错误断言、Governance 可观测性和运行 SHA，再讨论真实生产语义修复。
2. 公共 Provider 观察接缝先报最小方案；不改默认业务算法，不修改 canonical/oracle/旧报告，不用新目录拼接旧结果。
3. 真实模型授权仅覆盖 CT31 测试数据；禁止输出密钥，交付 `B3_R2_进度与初验.md` 和独立测试/新目录 smoke 证据。
