package com.github.noahshen.alcyone.context.vfs.core.storage

import com.github.noahshen.alcyone.context.vfs.NodeType
import java.io.InputStream
import java.time.Instant

/**
 * 存储写入模式。
 */
enum class StorageWriteMode {
    /** 仅在目标不存在时创建；已存在抛 ALREADY_EXISTS。 */
    CREATE_NEW,

    /** 仅在目标已存在时替换；不存在抛 NOT_FOUND。 */
    REPLACE_EXISTING,

    /** 目标不存在则创建，已存在则覆盖。 */
    UPSERT,
}

/**
 * 存储元属性，对应公共 StorageStat 的底层物理属性。
 */
data class StorageAttributes(
    val type: NodeType,
    val sizeBytes: Long? = null,
    val modifiedAt: Instant? = null,
)

/**
 * 单层列举条目。
 */
data class StorageEntry(
    val name: String,
    val type: NodeType,
    val attributes: StorageAttributes? = null,
)

/**
 * 一次性有界读取结果。
 */
class StorageContent(
    val bytes: ByteArray,
    val attributes: StorageAttributes,
)

/**
 * 流式读取包装，支持 AutoCloseable 资源释放。
 */
interface StorageStream : AutoCloseable {
    val attributes: StorageAttributes

    fun openStream(): InputStream
}

/**
 * 存储能力特征，严格只暴露 Core 关心的能力差异，不暴露 OpenDAL 原生 Capability。
 *
 * @property readOnly 存储只读。只读挂载的本地目录、服务器声明的 WebDAV 在**打开后端时即可得知**，
 * 预检据此在任何存储副作用之前拒绝变更（T10 §2.4）。默认 `false` 表示可写，不改变既有实现与既有测试。
 */
data class StorageCapabilities(
    val nativeFileMove: Boolean = true,
    val nativeDirectoryMove: Boolean = true,
    val createDirectory: Boolean = true,
    val boundedRead: Boolean = true,
    val readOnly: Boolean = false,
)

/**
 * Storage Port 接口，面向挂载根内的已解码相对路径 [StoragePath]。
 *
 * 不感知 VfsPath、URI、Node ID 或 Event，所有错误统一抛出 [com.github.noahshen.alcyone.context.vfs.VfsException]。
 */
interface Storage {
    /**
     * 一次性有界读取。目标为目录时直接抛 TYPE_MISMATCH；超限抛 LIMIT_EXCEEDED。
     */
    suspend fun read(
        path: StoragePath,
        maxBytes: Long,
    ): StorageContent

    /**
     * 流式读取。
     *
     * `maxBytes = null` 表示调用方不设上限；非 null 时表示读取上限，限额约束的是**实际读取到的内容**。
     * 返回的 [StorageStream] 持有底层资源，**由调用方负责关闭**。
     * 读取中如何计数并中止、异常如何转换、重复打开与关闭后的行为由 T12 在真实后端实现时确定。
     */
    suspend fun readStream(
        path: StoragePath,
        maxBytes: Long? = null,
    ): StorageStream

    /**
     * 获取指定路径的存储属性。不存在抛 NOT_FOUND。
     */
    suspend fun stat(path: StoragePath): StorageAttributes

    /**
     * 单层列举目录条目，不递归。目标为文件抛 TYPE_MISMATCH；不存在抛 NOT_FOUND。
     */
    suspend fun list(path: StoragePath): List<StorageEntry>

    /**
     * 按模式写入文件。不支持指定模式抛 UNSUPPORTED_OPERATION。
     *
     * 父路径异常时的错误码**条件式**：实现能确认是文件 / 目录类型冲突时抛 TYPE_MISMATCH；
     * 无法识别失败原因的真实 I/O 错误抛 STORAGE_ERROR。不要求额外探测。
     */
    suspend fun write(
        path: StoragePath,
        content: ByteArray,
        mode: StorageWriteMode,
    ): StorageAttributes

    /**
     * 创建目录。父路径异常时的错误码**条件式**：实现能确认是文件 / 目录类型冲突时抛 TYPE_MISMATCH；
     * 无法识别失败原因的真实 I/O 错误抛 STORAGE_ERROR。不要求额外探测。
     *
     * 目录已存在视为成功。Storage 不隐式为 [write] 补父目录。
     */
    suspend fun createDirectory(path: StoragePath)

    /**
     * 原生移动。目标已存在抛 ALREADY_EXISTS；源不存在抛 NOT_FOUND；后端不支持抛 UNSUPPORTED_OPERATION。
     */
    suspend fun move(
        source: StoragePath,
        target: StoragePath,
    ): StorageAttributes

    /**
     * 删除文件或目录。不存在抛 NOT_FOUND；非递归删除非空目录抛 DIRECTORY_NOT_EMPTY。
     */
    suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
    )

    /**
     * 当前存储能力。
     */
    fun capabilities(): StorageCapabilities
}
