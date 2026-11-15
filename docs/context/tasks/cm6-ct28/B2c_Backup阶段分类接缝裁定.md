# CT28 B2c Backup 阶段分类接缝裁定

## 本次修改（2026-11-11，批准一行 public 可见性）

- 核实 `failureStage(failure: Throwable?): ContextBackupFailureStage?` 参数与返回均为公共 API 类型，函数体只在 Runtime 内识别 internal `BackupStageFailure`；公开该函数不会暴露 internal 参数或返回类型。
- 仅允许 `context/runtime/.../backup/BackupRuntime.kt` 将顶层 `internal fun failureStage` 改为 `public fun failureStage`（显式 public 或省略可见性，按 Spotless/项目风格）。旧 SHA `25cfd1d0e35e31742843358bd4313dd32042aabf64d0a883a684ed3197bb626f`。
- 签名、when 算法、返回公共 enum 与 CancellationException→null 完全不变；`BackupStageFailure` 继续 internal。Server 只能导入该纯分类函数，不得反射类名、匹配 message/cause 文本或重建 Backup Runtime。
- 该函数是固定安全错误分类，不是新备份能力；不输出 Throwable、路径、URI、后端信息。除这一行外 Runtime/Backup 算法保持 SHA 内容不变，B2c 其余写集合仅 Server。B2c/CT28 未 DONE，CT29 不放行，无 Git 写操作。
