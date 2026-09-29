package alcyone.vfs

/**
 * VFS 协议级常量，来源是 T01 第 4.1 节与 T02 第 2.1、2.2 节已确认的取值。
 *
 * 这两项是**默认协议取值**：`SCHEME` 是逻辑 URI 的 scheme，`NAMESPACE_SEGMENTS` 是 VfsPath 允许的第一段。
 * 集中在此处是为了让协议取值不散落在路径与 URI 的业务逻辑里。
 *
 * 如果将来需要按部署覆盖（例如自定义 scheme 或增加顶级命名空间），由 T18 在 Runtime 层通过配置注入并
 * 覆盖这里的默认值；`vfs/api` 不读取外部配置文件，也不在解析时依赖运行时状态。
 */
internal object VfsProtocol {
    /** 逻辑 URI 的 scheme，识别时不区分大小写，序列化统一输出小写。 */
    const val SCHEME: String = "alcyone"

    /** 顶级逻辑命名空间，只接受这两个小写名称作为 VfsPath 的第一段。 */
    val NAMESPACE_SEGMENTS: Set<String> = setOf("memory", "resources")
}
