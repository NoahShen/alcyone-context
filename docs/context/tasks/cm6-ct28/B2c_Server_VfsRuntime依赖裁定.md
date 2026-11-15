# CT28 B2c Server vfs.runtime 依赖裁定

## 本次修改（2026-11-11，批准 testImplementation 提升 implementation）

- managed 入口调用既有公共 `AlcyoneContextFactory.create(contextConfig, contextConfig.vfsConfig)`，其签名含 `VfsRuntimeConfig`；Runtime 对 vfs.runtime 是 implementation，Server 生产编译必须能看到该签名类型。当前 Server 只有 testImplementation，Lead/Worker 已暂停且未绕过签名。
- 仅允许 `context/server/build.gradle.kts` 将 `testImplementation(projects.vfs.runtime)` 改为 `implementation(projects.vfs.runtime)`，保留一行、不重复声明；旧 SHA `499ea80c841f87ef70c81ddcbb17f8bd1910dfbafae1b8a11eeee1d576bc2f03`。更新原“生产 Server 不直接依赖 VFS 类型”的注释为准确说明：依赖仅满足 managed 公共 Factory 签名，不直接访问 VFS。
- 不新增版本、插件或其他模块依赖；Server 生产不得 import VFS 类型、直接创建/操作 VFS 或 JDBC，业务仍只经 AlcyoneContext。Backup public 分类接缝新 SHA `dc737edb2dab0e7e9f0f6f7d17f601d11602df9cb3dc076d46a6adf1fcfdf0a9` 已核对。
- B2c/CT28 未 DONE，CT29 不放行，无 Git 写操作。交付须附 Server production compileClasspath/源码 import 边界及全套验收。
