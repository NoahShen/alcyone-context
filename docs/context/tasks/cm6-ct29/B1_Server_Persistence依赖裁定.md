# CT29 B1 Server Persistence 依赖裁定

## 本次修改（2026-11-11，批准一行 implementation）

- StandaloneCommand 直接调用新公共 `ContextOfflineBackup.snapshot`；Runtime 对 persistenceSqldelight 为 implementation，不能向 Server 暴露该类型。当前源码只 import 公共 Offline Backup，无 JDBC/SQLDelight/其他 Persistence 操作。
- 仅允许 `context/server/build.gradle.kts` 新增 `implementation(projects.context.persistenceSqldelight)`；旧 SHA `00da758a396eb7ecbfcc8129e34e0fb1d908df67b8bb6bd7ea1caaab58a120dc`。不新增版本、插件或其他依赖，不改变 Runtime 的 implementation 边界。
- Server 不得直接 JDBC/SQL、创建 Repository/Executor 或调用 internal Persistence；备份仍只经公共 Offline Backup，init 只经公共 Runtime Factory。Xerial 已证实可直接写 CREATE_NEW 空目标，不改 ContextSqliteBackup。
- 本批准不替代 B1 行为复核；B2/B3 未放行，无 Git 写操作。交付附依赖/生产 import 边界及完整测试。
