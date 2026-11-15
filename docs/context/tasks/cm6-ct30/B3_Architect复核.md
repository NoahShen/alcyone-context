# CT30 B3 Architect 独立复核

## 本次修改（2026-11-12，R1 CLOSED，B3 APPROVED / DONE）

- 独立复核旧生产红例、临时文件身份修复、macOS arm64 目录同步能力、定向绿测、全量日志与同窗 XML；R1 的 foreign staging 被误删缺陷已关闭。
- **CT30 B3 APPROVED / DONE。** 仅放行 B4 只读验收方案，不放行 CT31/CT32；真实断电、Linux、硬件 flush 与 WebDAV durable 仍不宣称通过。无 Git 写操作。

## 1. 独立证据

- 旧生产 SHA `916c87c8…` 的 `red-publish-authoritative.failure.xml`：`10 tests / 2 failures / 0 errors / 0 skipped`，命令 exit 1；替换 staging 后 IOException 与 CancellationException 两个用例均真实失败。
- 修复后三个允许文件 SHA 与交付一致：`LocalFsStorage.kt b4b5e45d…`、`LocalFsPublishNewTest.kt acf039418…`、`LocalFsSafeReplaceTest.kt f45695f8…`；`ManagedAlcyoneHttpServerTest.kt` 保持 `d54bf86b…`。
- `final-scope-publish.xml 13` + `final-scope-safe-replace.xml 15` = **28/28**；macOS arm64 实测输出为 `actual=true capability=true`，不把该结果外推到 Linux。
- 第一次 `IC1a3m/final.stdout` 为 `BUILD SUCCESSFUL in 4m 5s`、`137 actionable tasks: 137 executed`，exit 0；第二次 `LxlMeb/check.stdout` 为 4m20s、137/137，exit 0。第二次是已记录并冻结的重复执行流程错误，不提高验收等级，未启动第三次。
- Architect 直接聚合第二次同窗 XML：14 模块 **312 XML / 2324 tests / 0 failures / 0 errors / 0 skipped**；时间戳 `06:17:48.508Z`～`06:22:00.151Z`，均在第二次全量窗口内。

## 2. 计数对账

源码测试声明不是简单只数独占一行的 `@Test`：实际为 **2313 个独立行声明 + 11 个 `@Test fun …` 同行声明 = 2324**，与执行 XML 一致；此前漏数的是同行声明，并非动态测试或隐藏失败。两目标类在全量 XML 中仍为 13 + 15。

## 3. 代码结论

- `CREATE_NEW` 后、读取输入前取得非空临时文件 fileKey；null/读取失败禁止发布并保留未知归属 staging。
- expected/actual 为 null 均不匹配；替换后的普通文件和符号链接不会被当成自己创建的 staging 删除。
- hard-link 发布后先同步父目录，再清理仍属本次身份的 staging；发布后同步/清理失败保新并至少 `PARTIAL`，取消原样传播、输入关闭与 suppressed 不丢失。
- safeReplace rename 后目录同步、cleanup 同步与 capability false 的既有原子非 durable 路径保持；WebDAV 未宣称 durable/atomic publishNew。
- 这是最佳努力崩溃持久性边界，不是任意并发外部替换防护，也未启用默认 Runtime 自动恢复或可靠消费。

## 4. 放行与限制

B3 DONE；B4 只读验收方案可以开始，按 [方案核对](B4_方案核对.md) 冻结证据矩阵，不能借验收改名目继续开发。B4 的完成仍须独立 Architect 复核；CT30/CM6 尚未 DONE，CT31/CT32 未放行。

复核人：当前独立 Architect；依据 Worker/Lead 产物之外的直接日志、XML、SHA 与源码检查。
