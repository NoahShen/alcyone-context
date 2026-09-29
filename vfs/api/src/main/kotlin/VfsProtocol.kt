package alcyone.vfs

/**
 * VFS 协议级常量，来源是 T01 第 4.1 节与 T02 第 2.1、2.2 节已确认的取值。
 *
 * 这里只保留协议本身：URI 的 scheme。集中在此处是为了让协议取值不散落在路径与 URI 的业务逻辑里。
 *
 * 顶层命名空间**不在**这里声明：T02 第 2、3 节已确认命名空间与虚拟目录由 Runtime 配置提供，
 * API 不检查第一段的名称，也不查询配置，因此不存在可被覆盖的默认集合。
 * `vfs/api` 不读取外部配置文件，也不在解析时依赖运行时状态。
 */
internal object VfsProtocol {
    /** 逻辑 URI 的 scheme，识别时不区分大小写，序列化统一输出小写。 */
    const val SCHEME: String = "alcyone"
}
