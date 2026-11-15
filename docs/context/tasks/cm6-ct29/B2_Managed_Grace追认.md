# CT29 B2 Managed CIO grace 最小修正追认

## 本次修改（2026-11-11，仅一处 stopSuspend 参数追认）

- 核实 Managed 当前 SHA `9679ba8136ae2160e9b6e66e1829fadf428f835bf5a943cf49dc877ccebe5616`，相对批准 `0bad2059…44f0` 仅 closeOnce 的 `stopSuspend(gracePeriodMillis=0, timeoutMillis=Long.MAX_VALUE)` 改为 `gracePeriodMillis=1_000`，其余不变。
- 逻辑请求完成仍由 registry 关闭准入并 awaitDrained 决定，发生在 CIO stop 之前；1 秒仅允许已提交响应网络 flush/idle keep-alive 收口，不是 drain 成功信号。CIO 真终止 timeout 仍 Long.MAX_VALUE，Runtime close 不会在一秒后盲目执行。
- 真实 installDist 4 MiB 响应收到 1 KiB 后发信号、完整读取、TERM/INT 0 和 SQLite 重开证据支持该最小修正，故明确追认；不是放宽未来未授权修改。B2 仍未 DONE，Lead 必须继续四模块/全仓/SHA/边界复核，B3/CT30～CT32 不放行，无 Git 写操作。
