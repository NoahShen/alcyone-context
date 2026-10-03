package com.github.noahshen.alcyone.context.vfs.core.event

import com.github.noahshen.alcyone.context.common.newUuidV7
import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.VfsEvent
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.core.repository.EventRecord
import java.time.Instant

/**
 * 事件生成入口：给「已经成功的状态变更」补一个事件 ID 和一个时间，其余字段由调用方给。
 *
 * 例：文件内容已经写好，Node 也登记好了，调用方挑 `FILE_WRITTEN`、把 Node ID 和逻辑 URI 填进来，
 * 拿到一条 [EventRecord]，和 Node 状态放进同一个事务提交。
 *
 * **选哪种类型不归这里管**：T15～T23 各自决定（写入选 `FILE_WRITTEN`、移动选 `FILE_MOVED`、
 * 改 Metadata 选 `METADATA_UPDATED`）。本类只保证字段自洽 + ID 是 UUIDv7 + 时间是毫秒。
 *
 * 懒注册不发事件，所以这里没有 `NODE_REGISTERED`；事件里也不放文件内容、凭据或 Agent 身份。
 *
 * @param clock 取当前时间；测试换成固定值好断言时间。
 */
class EventFactory(
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * 造一条事件记录。
     *
     * 移动事件（`FILE_MOVED` / `DIRECTORY_MOVED`）的 [uri] **必须是目标位置**，且 [sourceUri] / [targetUri] 都要填；
     * 其余事件不许填这两个字段——写错了在这里就报错，别等通知出去才发现字段对不上。
     *
     * @param nodeId 可以为空：查询不到有效 Node 的删除事件就没有 Node ID。
     * @param operationId 只用于诊断，不建立独立的操作恢复日志。
     * @throws IllegalArgumentException 移动事件的三个 URI 字段不自洽。
     */
    fun newRecord(
        type: VfsEventType,
        nodeId: NodeId?,
        uri: VfsUri,
        operationId: String? = null,
        sourceUri: VfsUri? = null,
        targetUri: VfsUri? = null,
    ): EventRecord {
        if (type.isMove()) {
            require(sourceUri != null && targetUri != null && uri == targetUri) {
                "$type 的 uri 必须是目标位置，且 sourceUri / targetUri 都要填：uri=$uri sourceUri=$sourceUri targetUri=$targetUri"
            }
        } else {
            require(sourceUri == null && targetUri == null) {
                "只有移动事件能填 sourceUri / targetUri，$type 不该带：sourceUri=$sourceUri targetUri=$targetUri"
            }
        }
        return EventRecord(
            id = VfsEventId.parse(newUuidV7().toString()),
            type = type,
            nodeId = nodeId,
            // 生成本身就按毫秒取整：库里存的和后面通知出去的会是同一个时间，不会差出零点几毫秒
            occurredAt = toStoredMillis(clock()),
            uri = uri,
            operationId = operationId,
            sourceUri = sourceUri,
            targetUri = targetUri,
        )
    }

    /**
     * 造一条移动事件：源和目标给全，`uri` 自动就是目标位置。
     *
     * 例：`/resources/a.txt` 改名成 `/resources/b.txt`，`newMove(FILE_MOVED, id, source, target)`
     * 得到 `uri = /resources/b.txt`、`sourceUri = /resources/a.txt`、`targetUri = /resources/b.txt`。
     */
    fun newMove(
        type: VfsEventType,
        nodeId: NodeId?,
        sourceUri: VfsUri,
        targetUri: VfsUri,
        operationId: String? = null,
    ): EventRecord = newRecord(type, nodeId, uri = targetUri, operationId = operationId, sourceUri = sourceUri, targetUri = targetUri)
}

/**
 * 持久化记录 → 通知用的事件：**同一个 ID、同一个类型、同一组 URI，不重新生成任何东西**。
 *
 * 例：`scope.events.append(record)` 提交成功后，通知 Consumer 的就是这一条的转换结果，
 * Consumer 拿到的 ID 和库里那一行完全对得上，可以直接拿来查库。
 *
 * 时间在这里对齐 SQLite 的毫秒精度（[toStoredMillis]），所以通知里的 [VfsEvent.occurredAt]
 * 永远等于库里那一行的值，哪怕这条记录是调用方自己拼的、带了纳秒。
 */
fun EventRecord.toVfsEvent(): VfsEvent =
    VfsEvent(
        id = id,
        type = type,
        nodeId = nodeId,
        occurredAt = toStoredMillis(occurredAt),
        uri = uri,
        operationId = operationId,
        sourceUri = sourceUri,
        targetUri = targetUri,
    )

/** 两种移动事件；其余事件既没有源也没有目标。 */
private fun VfsEventType.isMove(): Boolean = this == VfsEventType.FILE_MOVED || this == VfsEventType.DIRECTORY_MOVED

/** 事件日志表存的是 epoch 毫秒，转换一律按同一个算法取整，读写两端才对得上。 */
private fun toStoredMillis(instant: Instant): Instant = Instant.ofEpochMilli(instant.toEpochMilli())
