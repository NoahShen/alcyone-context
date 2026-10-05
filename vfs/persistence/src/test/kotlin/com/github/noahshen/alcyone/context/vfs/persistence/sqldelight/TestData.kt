package com.github.noahshen.alcyone.context.vfs.persistence.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

/** 测试基线时间；表里按 epoch 毫秒存储，用它才能得到稳定的回读值。 */
internal val TEST_NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")

internal fun testRecord(
    path: String,
    type: NodeType = NodeType.FILE,
    physical: Boolean = true,
    at: Instant = TEST_NOW,
    id: NodeId = NodeId.parse(newUuidV7().toString()),
): NodeRecord = NodeRecord(id, VfsPath.parse(path), type, physical, at, at)

internal fun testEvent(
    nodeId: NodeId?,
    uri: String,
    type: VfsEventType = VfsEventType.FILE_CREATED,
    occurredAt: Instant = TEST_NOW,
    operationId: String? = null,
    sourceUri: String? = null,
    targetUri: String? = null,
    id: VfsEventId = VfsEventId.parse(newUuidV7().toString()),
): EventRecord =
    EventRecord(
        id = id,
        type = type,
        nodeId = nodeId,
        occurredAt = occurredAt,
        uri = VfsUri.parse(uri),
        operationId = operationId,
        sourceUri = sourceUri?.let(VfsUri::parse),
        targetUri = targetUri?.let(VfsUri::parse),
    )

internal fun VfsStateDatabase.activeNodeCount(): Long = countOf("SELECT count(*) FROM node WHERE deleted_at IS NULL")

internal fun VfsStateDatabase.deletedNodeCount(): Long = countOf("SELECT count(*) FROM node WHERE deleted_at IS NOT NULL")

internal fun VfsStateDatabase.eventCount(): Long = countOf("SELECT count(*) FROM event")

internal fun VfsStateDatabase.metadataCount(): Long = countOf("SELECT count(*) FROM metadata")

private fun VfsStateDatabase.countOf(sql: String): Long =
    driver
        .executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            0,
            null,
        ).value

/** 事件表原始行；列顺序与 `Event.sq` 的表定义一致。Core 的 EventRepository 没有读接口，这里直查驱动。 */
internal data class EventRow(
    val eventId: String,
    val eventType: String,
    val nodeId: String?,
    val occurredAt: Long,
    val uri: String,
    val operationId: String?,
    val sourceUri: String?,
    val targetUri: String?,
)

internal fun VfsStateDatabase.eventRows(): List<EventRow> =
    driver
        .executeQuery(
            null,
            "SELECT event_id, event_type, node_id, occurred_at, uri, operation_id, source_uri, target_uri FROM event",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            add(
                                EventRow(
                                    eventId = cursor.getString(0)!!,
                                    eventType = cursor.getString(1)!!,
                                    nodeId = cursor.getString(2),
                                    occurredAt = cursor.getLong(3)!!,
                                    uri = cursor.getString(4)!!,
                                    operationId = cursor.getString(5),
                                    sourceUri = cursor.getString(6),
                                    targetUri = cursor.getString(7),
                                ),
                            )
                        }
                    },
                )
            },
            0,
            null,
        ).value

/**
 * 直写挂载行。T18 之后 Runtime 已有正式写入入口（[SqliteMountRepository.replaceAll]），
 * 这里留给「造一条旧库形状的行」用（例如迁移测试）。
 */
internal fun VfsStateDatabase.insertMount(
    path: String,
    storageKey: String,
) {
    driver.execute(null, "INSERT INTO mount (vfs_path, storage_key) VALUES (?, ?)", 2) {
        bindString(0, path)
        bindString(1, storageKey)
    }
}

/**
 * 代理真实 [Connection]：每条 `prepareStatement(sql)` 交给 [aroundPrepare]，其余方法原样转发。
 *
 * 为什么拦在这一层：`JdbcDriver.execute` 的默认实现是 final 的，它直接调 `Connection.prepareStatement`，
 * 没有可覆盖的钩子。取消与失败路径靠自然调度抢不出来，只能在这个点上确定性地放行或抛错。
 *
 * **控制点是「语句已准备好」这一刻**：JDBC 已经返回 [java.sql.PreparedStatement]，但 `execute()` 还没发生，
 * 真正的执行在钩子放行之后由 `JdbcDriver.execute` 发出。别把这个时刻当成「语句已执行」。
 */
internal fun hookedConnection(
    delegate: Connection,
    aroundPrepare: (sql: String, prepare: () -> Any?) -> Any?,
): Connection =
    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        val prepare = { method.invoke(delegate, *(args ?: emptyArray())) }
        if (method.name == "prepareStatement" && args != null) {
            aroundPrepare(args[0] as String, prepare)
        } else {
            prepare()
        }
    } as Connection

/**
 * 真实 SQLite + 带钩子的连接，供需要控制语句时机的用例使用；已建好当前版本 Schema。
 *
 * [aroundPrepare] 只作用在 `prepareStatement` 上，控制点是语句准备完成、尚未 `execute()` 的那一刻。
 */
internal fun hookedState(aroundPrepare: (sql: String, prepare: () -> Any?) -> Any?): VfsStateDatabase {
    val driver =
        SingleConnectionJdbcDriver(
            hookedConnection(DriverManager.getConnection("jdbc:sqlite:", Properties()), aroundPrepare),
        )
    VfsDatabase.Schema.create(driver).value
    return VfsStateDatabase(VfsDatabase(driver), driver)
}

/** 只为「关闭失败」构造的驱动：其他调用一律转给真实驱动。 */
internal class CloseFailingDriver(
    private val delegate: JdbcDriver,
) : JdbcDriver() {
    override fun getConnection(): Connection = delegate.getConnection()

    override fun closeConnection(connection: Connection) = delegate.closeConnection(connection)

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        delegate.addListener(*queryKeys, listener = listener)
    }

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        delegate.removeListener(*queryKeys, listener = listener)
    }

    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)

    override fun close(): Unit = throw IllegalStateException("close boom")
}
