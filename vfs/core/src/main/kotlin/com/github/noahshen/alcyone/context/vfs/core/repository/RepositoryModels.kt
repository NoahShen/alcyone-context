package com.github.noahshen.alcyone.context.vfs.core.repository

import com.github.noahshen.alcyone.context.vfs.NodeId
import com.github.noahshen.alcyone.context.vfs.NodeType
import com.github.noahshen.alcyone.context.vfs.VfsEventId
import com.github.noahshen.alcyone.context.vfs.VfsEventType
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.time.Instant

/**
 * Node 状态记录。
 *
 * @property id 稳定 Node 标识
 * @property path 当前逻辑路径
 * @property type 节点类型
 * @property physical 是否有物理存储映射。虚拟目录为 false，真实文件/物理目录为 true (G9)
 * @property registeredAt 注册时间
 * @property updatedAt 最后更新时间
 */
data class NodeRecord(
    val id: NodeId,
    val path: VfsPath,
    val type: NodeType,
    val physical: Boolean,
    val registeredAt: Instant,
    val updatedAt: Instant,
)

/**
 * 挂载点记录。本轮保持纯读，不定义写入接口。
 *
 * 首版**不提供可配置的只读挂载**：后端本身只读或拒绝访问，按真实能力与实际错误处理，
 * 不由 Core 用配置位拦截。某个存储是否可写属 Adapter 的能力探测范围（T10 / T12）。
 *
 * @property path 挂载逻辑路径
 * @property storageKey 底层存储实例标识（不泄露凭据或具体 URL，仅内部唯一键）
 */
data class MountRecord(
    val path: VfsPath,
    val storageKey: String,
)

/**
 * 事件持久化记录。
 *
 * @property id 事件标识
 * @property type 事件类型
 * @property nodeId 关联的 Node 标识，移动/删除等操作可携带
 * @property occurredAt 发生时间
 * @property uri 主要资源 URI
 * @property operationId 关联的操作 ID，随事件一起保存，仅用于诊断；不建立独立的操作恢复日志
 * @property sourceUri 移动源 URI
 * @property targetUri 移动目标 URI
 */
data class EventRecord(
    val id: VfsEventId,
    val type: VfsEventType,
    val nodeId: NodeId?,
    val occurredAt: Instant,
    val uri: VfsUri,
    val operationId: String? = null,
    val sourceUri: VfsUri? = null,
    val targetUri: VfsUri? = null,
)
