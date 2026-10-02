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
 * 同步阻塞调用的 IO 执行环境与错误映射（T12 §2.5）。与 T11 的 stateCall 同思路，但错误码是 Storage 自己一套。
 *
 * 公开消息只说明「哪个本地存储操作失败了」，不拼接物理路径、凭据或后端原始响应；原始异常挂在 cause 上。
 */

/** 在 IO 调度器上执行一次阻塞的存储调用。
 *
 * [onEnter] 是测试专用可观测点，生产恒为 `null`：阻塞调用是否离开调用方线程、以及
 * 「资源已取得但交回前被取消」这两个窗口都发生在 [withContext] 边界上，别处看不见。
 */
internal suspend fun <T> storageCall(
    operation: String,
    effect: VfsEffect = VfsEffect.NONE,
    onEnter: ((String) -> Unit)? = null,
    block: () -> T,
): T =
    withContext(Dispatchers.IO) {
        onEnter?.invoke(operation)
        mapStorageErrors(operation, effect, block)
    }

/**
 * 堵上「资源已取得、但还没交给调用方」的取消窗口（T12 R2）。
 *
 * 资源在 IO 执行块里创建（[storageCall] 有 prompt cancellation：块跑完、切回时若协程已取消就抛
 * [CancellationException]），而块内的 `try/catch` 接不住这个异常——它抛在块外。
 * 于是调用方拿不到资源，块内的 catch 也不会执行，native 句柄就泄漏了。
 *
 * 做法：块内把取得的资源登记到**外层可见**的变量（[release] 读它），外层捕获取消后释放并原样重抛。
 * 与 `vfs/persistence` 的 `SqliteUnitOfWork` 是同一个模式。
 *
 * 登记必须发生在 [storageCall] 的 IO 块**内部**：块成功返回、切回时若已取消，[withContext] 抛的异常
 * 会吃掉块的返回值，块内登记的变量却已经写好了，所以释放路径拿得到资源。
 *
 * 清理失败只 [Throwable.addSuppressed]，不覆盖原始取消异常。
 *
 * 例：`create` 里 `Operator.of` 成功但协程在返回前被取消 → `release` 关掉 Operator，调用方仍然只看到取消。
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
 * 不吞异常、不猜原因：[CancellationException] 与已经是 VFS 契约的异常原样传播，
 * 其余按 [OpenDALException.Code] 与 JDK I/O 异常映射到 [VfsErrorCode]。
 */
internal fun <T> mapStorageErrors(
    operation: String,
    effect: VfsEffect = VfsEffect.NONE,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VfsException) {
        throw e
    } catch (e: OpenDALException) {
        throw storageFailure(operation, effect, openDalCode(e), e)
    } catch (e: NoSuchFileException) {
        throw storageFailure(operation, effect, VfsErrorCode.NOT_FOUND, e)
    } catch (e: AccessDeniedException) {
        throw storageFailure(operation, effect, VfsErrorCode.STORAGE_ACCESS_DENIED, e)
    } catch (e: IOException) {
        throw storageFailure(operation, effect, VfsErrorCode.STORAGE_ERROR, e)
    }

/**
 * OpenDAL 错误码到 VFS 错误码的映射。
 *
 * 实测事实（OpenDAL 0.50.6 / fs / macOS arm64）：`ConditionNotMatch` 是 `if_not_exists` 条件写命中已存在文件；
 * 目录 rename 返回 `IsADirectory`（本 Adapter 先自行判定并报 UNSUPPORTED_OPERATION，这里只是兜底）；
 * 删除非空目录返回 `Unexpected`，因此非空判断由 Adapter 先列举完成。
 */
private fun openDalCode(error: OpenDALException): VfsErrorCode =
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

private fun storageFailure(
    operation: String,
    effect: VfsEffect,
    code: VfsErrorCode,
    cause: Throwable,
): VfsException = VfsException(code, "local storage $operation failed ($code)", effect = effect).apply { initCause(cause) }
