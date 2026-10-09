package com.github.noahshen.alcyone.context.vfs.storage.opendal

import com.github.noahshen.alcyone.context.vfs.VfsEffect
import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.opendal.OpenDALException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/*
 * 本地存储的错误转换：把 OpenDAL 的报错翻译成 VFS 的错误码。
 *
 * 错误消息只说「哪个操作失败了」，不带磁盘路径或后端原文；后端异常挂在 cause 上。
 */

/**
 * 在后台线程上执行一次存储操作，并把异常翻译成 VFS 错误码。
 *
 * 操作本身是阻塞的（比如读磁盘），所以必须挪到 [Dispatchers.IO]，
 * 否则会把调用方的线程占住。
 *
 * [onEnter] 只给测试用，生产恒为 `null`；[backend] 只影响错误消息里的后端名。
 */
internal suspend fun <T> storageCall(
    operation: String,
    effect: VfsEffect = VfsEffect.NONE,
    onEnter: ((String) -> Unit)? = null,
    backend: String = "local storage",
    block: () -> T,
): T =
    withContext(Dispatchers.IO) {
        onEnter?.invoke(operation)
        mapStorageErrors(operation, effect, backend, block)
    }

/**
 * 打开文件流这类操作，如果中途被取消，必须把已经打开的东西关掉。
 *
 * 场景：已经打开了文件，正准备返回给调用方，这时协程被取消了。
 * 调用方拿不到这个文件流，如果没人关它，文件句柄就一直被占着。
 *
 * 做法：块内把打开结果记到外面能看到的变量里（[release] 会用到它），
 * 块外捕获取消异常，关掉之后再原样抛出去。和 `vfs/persistence` 里的 `SqliteUnitOfWork` 同一个写法。
 *
 * 记变量的动作要写在 [storageCall] 的块里面：块跑完、切回时如果协程已取消，
 * 返回值会被丢掉，但块里记的变量已经写好了，所以关得到。
 *
 * 关闭失败只记到 [Throwable.addSuppressed]，不会盖掉原来的取消异常。
 */
internal suspend fun <T> handoffOrRelease(
    release: () -> Unit,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        try {
            release()
        } catch (cleanup: Throwable) {
            e.addSuppressed(cleanup)
        }
        throw e
    }

/**
 * 把底层异常翻译成 [VfsException]。
 *
 * 取消异常和已经翻译好的 VFS 异常原样抛出，其余按错误码对照表转换。
 * [backend] 只用来写错误消息里的后端名（本地盘 / WebDAV）。
 */
internal fun <T> mapStorageErrors(
    operation: String,
    effect: VfsEffect = VfsEffect.NONE,
    backend: String = "local storage",
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VfsException) {
        throw e
    } catch (e: OpenDALException) {
        throw storageFailure(operation, effect, openDalCode(e), backend, e)
    } catch (e: NoSuchFileException) {
        throw storageFailure(operation, effect, VfsErrorCode.NOT_FOUND, backend, e)
    } catch (e: AccessDeniedException) {
        throw storageFailure(operation, effect, VfsErrorCode.STORAGE_ACCESS_DENIED, backend, e)
    } catch (e: IOException) {
        throw storageFailure(operation, effect, VfsErrorCode.STORAGE_ERROR, backend, e)
    }

/**
 * OpenDAL 错误码到 VFS 错误码的对照（OpenDAL 0.50.6，fs 与 webdav 服务，macOS arm64 实测）。
 *
 * 两条特别注意：
 * - `IsADirectory` 与「删除非空目录返回 `Unexpected`」：[LocalFsStorage] / [WebDavStorage] 自己会先判断，
 *   这里只是兑底；
 * - **HTTP 401 单独认**：OpenDAL 0.50.6 的 webdav 服务把 401 报成 `Unexpected`，错误消息里带着响应行
 *   `status: 401`。认这一条，认证失败才落到 `STORAGE_ACCESS_DENIED`——这是按观察到的后端行为写的对照，
 *   不是从 HTTP 语义推断的通用规则。
 */
private fun openDalCode(error: OpenDALException): VfsErrorCode =
    if ("status: 401" in (error.message ?: "")) {
        VfsErrorCode.STORAGE_ACCESS_DENIED
    } else {
        when (error.code) {
            OpenDALException.Code.NotFound -> VfsErrorCode.NOT_FOUND
            OpenDALException.Code.AlreadyExists, OpenDALException.Code.ConditionNotMatch -> VfsErrorCode.ALREADY_EXISTS
            OpenDALException.Code.IsADirectory, OpenDALException.Code.NotADirectory -> VfsErrorCode.TYPE_MISMATCH
            OpenDALException.Code.PermissionDenied -> VfsErrorCode.STORAGE_ACCESS_DENIED
            OpenDALException.Code.Unsupported -> VfsErrorCode.UNSUPPORTED_OPERATION
            OpenDALException.Code.ConfigInvalid -> VfsErrorCode.INVALID_ARGUMENT
            OpenDALException.Code.IsSameFile, OpenDALException.Code.Conflict -> VfsErrorCode.CONFLICT
            OpenDALException.Code.RangeNotSatisfied -> VfsErrorCode.LIMIT_EXCEEDED
            OpenDALException.Code.RateLimited, OpenDALException.Code.Unexpected -> VfsErrorCode.STORAGE_ERROR
        }
    }

private fun storageFailure(
    operation: String,
    effect: VfsEffect,
    code: VfsErrorCode,
    backend: String = "local storage",
    cause: Throwable,
): VfsException = VfsException(code, "$backend $operation failed ($code)", effect = effect).apply { initCause(cause) }
