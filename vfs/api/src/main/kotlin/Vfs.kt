package alcyone.vfs

/**
 * Context System 使用的统一文件访问接口。
 *
 * 接口只接收文件操作参数；Agent 目录分配、共享范围和访问隔离由 Context System 负责。
 * 本接口在 `vfs/api` 只声明签名，实现位于 `vfs/core` / `vfs/runtime`。
 */
interface Vfs {
    /** 只读取文件，不懒注册，不产生变更事件；目录输入返回 TYPE_MISMATCH。 */
    suspend fun read(
        uri: VfsUri,
        options: ReadOptions = ReadOptions(),
    ): ByteArray

    /** 创建或更新文件并持久化 Node；返回信息不额外调用 Storage stat，`storage` 可空。 */
    suspend fun write(
        uri: VfsUri,
        content: ByteArray,
        options: WriteOptions = WriteOptions(),
    ): NodeInfo

    /** 默认取得底层属性；Node 不存在时可懒注册，因此可能写入状态库。 */
    suspend fun stat(
        uri: VfsUri,
        options: StatOptions = StatOptions(),
    ): NodeInfo

    /** 查询已注册 Node 的逻辑信息；未知 ID 返回 NOT_FOUND，不隐式寻找外部文件。 */
    suspend fun getNode(id: NodeId): NodeInfo

    /** 单层列举直接子项，不递归、不注册 Node、不保证排序。 */
    suspend fun list(uri: VfsUri): List<VfsEntry>

    /** 移动文件或目录；目标已存在则拒绝，保持 Node ID。 */
    suspend fun move(source: VfsUri, target: VfsUri): NodeInfo

    /** 删除；默认非递归，非空目录返回 DIRECTORY_NOT_EMPTY，不存在返回 NOT_FOUND。 */
    suspend fun delete(
        uri: VfsUri,
        options: DeleteOptions = DeleteOptions(),
    )

    /** 只查询已注册 Node 的逻辑状态；未设置时返回空 Metadata。 */
    suspend fun getMetadata(id: NodeId): NodeMetadata

    /** 整体替换 Metadata，空对象清空；不提供隐式字段合并。 */
    suspend fun setMetadata(id: NodeId, metadata: NodeMetadata)
}
