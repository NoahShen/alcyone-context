package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import com.github.noahshen.alcyone.context.vfs.VfsErrorCode
import com.github.noahshen.alcyone.context.vfs.VfsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 IO 调度器上执行一次 JDBC 调用；数据库失败统一映射为 [VfsErrorCode.STATE_ERROR]（T11 §2.5）。
 *
 * 自动提交与事务内两条路径都用它。事务内不要求语句留在某个线程上——整库只有一条连接，
 * 见 [SingleConnectionJdbcDriver]。
 */
internal suspend fun <T> stateCall(block: () -> T): T = withContext(Dispatchers.IO) { mapStateErrors(block) }

/**
 * 不吞异常、不猜测原因：原始异常挂在 cause 上，对外消息只说明失败发生在状态库。
 * [CancellationException] 与已经是 VFS 契约的异常原样传播。
 */
internal fun <T> mapStateErrors(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VfsException) {
        throw e
    } catch (e: Exception) {
        throw VfsException(
            VfsErrorCode.STATE_ERROR,
            "state store operation failed (${e::class.simpleName})",
        ).apply { initCause(e) }
    }
