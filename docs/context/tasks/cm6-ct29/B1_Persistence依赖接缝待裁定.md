# CT29 B1 Persistence 依赖接缝待裁定

## 本次修改（2026-11-11，Architect 批准，Worker 恢复）

- Architect批准仅Server新增 `implementation(projects.context.persistenceSqldelight)`，旧SHA `00da758a…a120dc`；无版本/插件/其他依赖。见 [正式裁定](B1_Server_Persistence依赖裁定.md)。
- Server不得直接JDBC/SQL/Repository/Executor/internal Persistence；备份只公共OfflineBackup，init只公共RuntimeFactory。Xerial预认领可用，不改ContextSqliteBackup。
- Worker继续完整验收并交生产import/依赖边界；B1未DONE，B2/B3不放行，无Git写。

## 历史修改（2026-11-11，Worker 报告，提交 Architect）

- Xerial已确认可直接备份CREATE_NEW预认领空目标，Offline定向4例通过；无需改ContextSqliteBackup或删除认领。
- Server只有 `implementation(context.runtime)`，Runtime对Persistence也是implementation，StandaloneCommand无法import公共 `ContextOfflineBackup`，upgrade编译阻断。
- 最小请求：仅 `context/server/build.gradle.kts` 新增 `implementation(projects.context.persistenceSqldelight)`；CLI只调用公共离线备份API，不直接JDBC/SQLDelight，不改Runtime/VFS/版本/Schema。
- Lead认可该最小依赖，已提交Architect；批准前不得绕过构建。其他新增文件按既有公共API修正，不改现有生产/测试。B1/CT29未DONE，无Git写。
