# CT28 B2c Backup 阶段分类接缝待裁定

## 本次修改（2026-11-11，Architect 批准，Worker 恢复）

- Architect 已批准仅 `failureStage` 一行 `internal → public`；签名/when算法/CE null不变，`BackupStageFailure`保持internal，Server只import纯分类函数。见 [正式裁定](B2c_Backup阶段分类接缝裁定.md)。
- 已通知Worker继续原B2c并记录新SHA；其余仅Server，不改公共SDK/Schema/VFS。B2c/CT28未DONE，CT29不放行，无Git写操作。

## 历史修改（2026-11-11，Worker 暂停，提交 Architect）

- Worker 按“确需 API/Runtime 扩展立即暂停”报告，尚未改生产/测试；B2c/CT28未DONE，CT29未启动，无Git写。
- 事实：`BackupRuntime.run` 的 SNAPSHOT/PRE_CHECK/POST_CHECK 失败直接抛 internal `BackupStageFailure`；同文件 `failureStage(Throwable?)` 为 internal，`context:server` 跨Gradle模块不可见。Server不能可靠映射具名500，也不得用类名/message反射。
- 最小请求：仅把 `BackupRuntime.kt:383` 的 `failureStage` 从 internal 改为 public；签名、算法、返回既有公共 `ContextBackupFailureStage?`、CE=null均不变，`BackupStageFailure`仍internal。当前SHA `25cfd1d0e35e31742843358bd4313dd32042aabf64d0a883a684ed3197bb626f`。
- Lead认可其为B2 D7既定阶段映射的最小必要接缝，已提交Architect；批准前Worker保持暂停，不实施其他代码。
