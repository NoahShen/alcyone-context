# CT30 B2 R1 Lead 初验

## 本次修改（2026-11-12，Architect R1 正式补证完成）

- 按 `B2_Architect复核.md` 仅修改两个测试文件：Persistence 增加真实 v3→v4 与失败回滚；integration 增加 poll/顺序、慢 handler 不阻塞 append、失败批 close。
- 五项生产 SHA 与 R1 前完全一致；独立 JVM 强杀、Core 四例、v1→v4 保持通过。B2 尚待 Architect 复核，CT30/CM6 未 DONE，无 Git 写操作。

## R1 映射与证据

| 门禁 | 正式测试与结果 |
| --- | --- |
| R1-1 poll/顺序 | `ReliableEventConsumerSqliteTest.poll without hints...`：无 hint，启动后追加同毫秒两条与倒时钟一条，收到 sequence 1/2/3 |
| R1-2 v3→v4 | `ReliableEventSqliteTest.real v3 journal...`：真实 v3 event/journal 直接迁移，rowid→1/2、全字段保留、后续 append=3 |
| R1-3 失败回滚 | `ReliableEventSqliteTest.failed v3 to v4...`：损坏 v3 重复 event_id 触发新 UNIQUE；user_version=3、旧 event/journal 完整，无 event_v4/cursor/sequence 残留 |
| R1-4 真实锁/失败 close | `slow handler...`：handler 挂起时真实 append 在 2 秒内完成，close 等释放且 next=2；`close after handler failure...`：cursor=1、handler 一次、无重试提交 |

失败回滚夹具明确去除旧 PK 以构造“可读取但含重复 event_id 的损坏 v3”，不是声称正常 v3 能写重复 ID；新 v4 UNIQUE 在真实迁移中拒绝。

## 独立验证

- fresh 定向：Core 4、Persistence 4、integration poll/close 3 + 独立 JVM 1，全绿；日志 `/tmp/ct30-b2-r1-directed.log`。
- 生产 SHA：`shasum -a 256 -c /tmp/ct30-b2-r1-production.sha` 五项全部 OK。
- 最终 `./scripts/check` exit 0，137/137 tasks，日志 `/tmp/ct30-b2-r1-check.log`。
- 14 模块 **2307=2307**，0 failure/error/skipped；Core 343、Persistence 62、integration-vfs 125，其余模块计数不变。

## 待 Architect 裁定

R1 是否 CLOSED、B2 是否 APPROVED / DONE；未放行 B3，CT30/CM6 完成仍需最终裁定。
