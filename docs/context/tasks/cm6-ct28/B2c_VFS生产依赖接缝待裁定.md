# CT28 B2c VFS 生产依赖接缝待裁定

## 本次修改（2026-11-11，Architect 批准，Worker 恢复）

- Architect 批准仅将现有 `testImplementation(projects.vfs.runtime)` 改为一行 `implementation(projects.vfs.runtime)`，不重复；注释仅满足managed公共Factory签名，无版本/插件/其他依赖。见 [正式裁定](B2c_Server_VfsRuntime依赖裁定.md)。
- Server生产不得import VFS类型或直接VFS/JDBC；Worker继续managed及全套验收，须交production compileClasspath/import边界证据。B2c/CT28未DONE，CT29不放行，无Git写。

## 历史修改（2026-11-11，Worker 暂停，提交 Architect）

- managed 必须调用既有公共 `AlcyoneContextFactory.create(ContextConfig, VfsRuntimeConfig)`；`context:runtime` 将 `projects.vfs.runtime` 声明为 implementation，`context:server` 只有 testImplementation，故生产 `ManagedAlcyoneHttpServer.kt` 无法访问签名中的 `VfsRuntimeConfig`。ContextConfigLoader公开签名不能绕过。
- 最小请求：仅 `context/server/build.gradle.kts` dependencies 新增 `implementation(projects.vfs.runtime)`；可保留/删除重复testImplementation，不修改版本/其他模块，不直接VFS/DB。旧SHA `499ea80c841f87ef70c81ddcbb17f8bd1910dfbafae1b8a11eeee1d576bc2f03`。
- Lead认可最小构建接缝，已提交Architect；Worker managed保持暂停。已批Backup failureStage一行完成，新SHA `dc737edb2dab0e7e9f0f6f7d17f601d11602df9cb3dc076d46a6adf1fcfdf0a9`。
- Admin POST生产已写但完整测试/managed待裁定；B2c/CT28未DONE，CT29未启动，无Git写。
