package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.PublishTempName
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import com.github.noahshen.alcyone.context.vfs.core.storage.Storage
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageAttributes
import com.github.noahshen.alcyone.context.vfs.core.storage.StoragePath
import java.io.InputStream

/**
 * Runtime 能拿在手上的存储 Adapter：既能当一块盘用（[Storage]），也能在关闭或初始化失败时释放（[AutoCloseable]）。
 *
 * 目前有两个实现：[LocalFsStorage] 与 [WebDavStorage]。组装代码只认这个类型，
 * 打开哪一种由宿主配置决定，这样组装处不需要到处把 [Storage] 和 [AutoCloseable] 来回转。
 * 具体后端的名字与凭据都不出现在这个接口上。
 */
interface StorageAdapter :
    Storage,
    AutoCloseable {
    val publishTempNames: com.github.noahshen.alcyone.context.vfs.core.storage.PublishTempNameFactory
        get() =
            com.github.noahshen.alcyone.context.vfs.core.storage
                .PublishTempNameFactory { PublishTempName.newName() }

    override suspend fun publishNew(
        path: StoragePath,
        input: InputStream,
        expectedSizeBytes: Long,
        maxBytes: Long,
        tempName: String,
    ): StorageAttributes {
        input.close()
        if (!capabilities().atomicPublishNew) {
            throw VfsException(VfsErrorCode.UNSUPPORTED_OPERATION, "publishNew is not supported by this storage backend")
        }
        return super.publishNew(path, input, expectedSizeBytes, maxBytes, tempName)
    }
}
