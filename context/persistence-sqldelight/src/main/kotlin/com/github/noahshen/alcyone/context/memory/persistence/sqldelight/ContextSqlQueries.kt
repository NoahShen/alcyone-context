package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

/**
 * Context 状态库的 SQL 常量（CT05）。
 *
 * 规则（架构任务书「实现边界」与 CT05 方案 §2）：
 * - **全部参数化**：任何调用方提供的值只能经 `?` 绑定，禁止把值拼进 SQL 文本；
 * - 只放单条语句：执行器 [SqlBoundaryGuard] 统一阻断事务控制与多语句；
 * - 不在此处做业务判断：幂等 / 冲突 / 空闲语义在 Ops 层完成；
 * - 时间列统一由 [ContextDbTime] 编码，不使用 `CURRENT_TIMESTAMP`。
 *
 * 本文件在 CT05-B1 只覆盖 Conversation；其余 Port 的 SQL 由 B2 追加。
 */
internal object ContextSqlQueries {
    /** `conversation_turn` 的固定列顺序；[ConversationTurnMapper] 的游标下标与之一一对应。 */
    private const val TURN_COLUMNS =
        "id, runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json, extraction_status"

    /** 按内部自增主键定位。 */
    const val SELECT_TURN_BY_ID = "SELECT $TURN_COLUMNS FROM conversation_turn WHERE id = ?"

    /** 按四元业务键定位（幂等与冲突判定的唯一入口）。 */
    const val SELECT_TURN_BY_BUSINESS_KEY =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? AND session_turn_id = ?"

    /**
     * 会话窗口（有锚点）：包含锚点自身（`timestamp = anchor AND id <= anchorId`），
     * 按 `timestamp DESC, id DESC` 取 limit 条，由 Ops 反序返回真实会话顺序。
     *
     * 参数顺序：runtime, agent, session, anchorTimestamp(<), anchorTimestamp(=), anchorId, limit。
     *
     * 为什么拆成两条而不是 `(? IS NULL OR ...)`：CT04 已验收的 `ScopedJdbc` 标量绑定白名单
     * 不支持 NULL（`setString(index, null)` 会在白名单转换处抛 NPE），而 CT04 保护层已冻结、
     * 不在 B1 修改范围内。因此「无锚点」用独立参数化 SQL，两条都保持参数化与索引可用。
     */
    const val SELECT_SESSION_WINDOW =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? " +
            "AND (timestamp < ? OR (timestamp = ? AND id <= ?)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT ?"

    /** 会话窗口（无锚点）：取最新 limit 条，参数顺序 runtime, agent, session, limit。 */
    const val SELECT_SESSION_WINDOW_LATEST =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? " +
            "ORDER BY timestamp DESC, id DESC LIMIT ?"

    /**
     * 严格前向窗口：仅返回 `(timestamp, id)` 大于锚点的同 Session Turn。
     *
     * 参数顺序：runtime, agent, session, afterTimestamp(>), afterTimestamp(=), afterId, limit。
     */
    const val SELECT_SESSION_WINDOW_AFTER =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? " +
            "AND (timestamp > ? OR (timestamp = ? AND id > ?)) " +
            "ORDER BY timestamp ASC, id ASC LIMIT ?"

    /** 最近一条 pending（按会话时间与内部 id 倒序）。 */
    const val SELECT_LATEST_PENDING =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? AND extraction_status = 'pending' " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1"

    /** pending 计数。 */
    const val COUNT_PENDING =
        "SELECT COUNT(*) FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? AND extraction_status = 'pending'"

    /** 空闲判定第一步：该会话是否还有 pending。 */
    const val EXISTS_PENDING =
        "SELECT EXISTS(SELECT 1 FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ? AND extraction_status = 'pending')"

    /** 空闲判定第二步：该会话**全状态**的最大 created_at（走 idx_turn_session_created_at）。 */
    const val MAX_CREATED_AT =
        "SELECT MAX(created_at) FROM conversation_turn " +
            "WHERE runtime_id = ? AND agent_id = ? AND session_id = ?"

    /**
     * 空闲会话枚举：候选必须有 pending，且该会话全状态 `MAX(created_at) <= idleBefore`。
     * 结果按业务键排序，保证调用方拿到稳定顺序。
     */
    const val SELECT_IDLE_SESSIONS =
        "SELECT runtime_id, agent_id, session_id FROM conversation_turn " +
            "GROUP BY runtime_id, agent_id, session_id " +
            "HAVING SUM(CASE WHEN extraction_status = 'pending' THEN 1 ELSE 0 END) > 0 " +
            "AND MAX(created_at) <= ? " +
            "ORDER BY runtime_id ASC, agent_id ASC, session_id ASC"

    /** 空表返回 null（Dreaming 固定扫描上界）。 */
    const val SELECT_MAX_TURN_ID = "SELECT MAX(id) FROM conversation_turn"

    /** 增量扫描：下界排除、上界包含，按 id 升序。 */
    const val SELECT_TURN_ID_RANGE =
        "SELECT $TURN_COLUMNS FROM conversation_turn " +
            "WHERE id > ? AND id <= ? ORDER BY id ASC LIMIT ?"

    /** 新 Turn 一律以 pending 入库；created_at 由调用方分配，不刷新既有行。 */
    const val INSERT_TURN =
        "INSERT INTO conversation_turn " +
            "(runtime_id, agent_id, session_id, session_turn_id, timestamp, created_at, messages_json, extraction_status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')"

    /** 插入后在同一连接/事务内取回自增主键。 */
    const val SELECT_LAST_INSERT_ROW_ID = "SELECT last_insert_rowid()"

    /** 条件状态更新：只有当前仍为 pending 才会命中。 */
    const val MARK_COMPLETE = "UPDATE conversation_turn SET extraction_status = 'complete' WHERE id = ? AND extraction_status = 'pending'"

    const val MARK_FAILED = "UPDATE conversation_turn SET extraction_status = 'failed' WHERE id = ? AND extraction_status = 'pending'"

    // =====================================================================
    // B2：Raw Memory / Organized Memory / Organization Pending / Checkpoint
    // =====================================================================

    /** `raw_memory` 的固定列顺序；[RawMemoryMapper] 的游标下标与之一一对应。 */
    private const val RAW_COLUMNS = "id, content, generated_by, sources_json, created_at"

    const val SELECT_RAW_BY_ID = "SELECT $RAW_COLUMNS FROM raw_memory WHERE id = ?"

    /** 批量精确查询：占位符数量由调用方集合大小决定，值全部绑定。 */
    fun selectRawByIds(count: Int): String = "SELECT $RAW_COLUMNS FROM raw_memory WHERE id IN (${placeholders(count)})"

    /** 历史分页：`created_at ASC, id ASC` 确定性顺序。 */
    const val SELECT_RAW_PAGE = "SELECT $RAW_COLUMNS FROM raw_memory ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?"

    /** Raw 不可变：只有插入，没有 update / delete 业务语句。 */
    const val INSERT_RAW = "INSERT INTO raw_memory (id, content, generated_by, sources_json, created_at) VALUES (?, ?, ?, ?, ?)"

    /** `organized_memory` 的固定列顺序；[OrganizedMemoryMapper] 的游标下标与之一一对应。 */
    private const val ORGANIZED_COLUMNS = "id, content, domains_json, created_at, updated_at"

    const val SELECT_ORGANIZED_BY_ID = "SELECT $ORGANIZED_COLUMNS FROM organized_memory WHERE id = ?"

    fun selectOrganizedByIds(count: Int): String = "SELECT $ORGANIZED_COLUMNS FROM organized_memory WHERE id IN (${placeholders(count)})"

    const val SELECT_ORGANIZED_PAGE =
        "SELECT $ORGANIZED_COLUMNS FROM organized_memory ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?"

    /** 来源正查：Organized -> Raw id（按 raw id 升序，保证稳定顺序）。 */
    const val SELECT_SOURCES_BY_ORGANIZED =
        "SELECT raw_memory_id FROM organized_memory_source WHERE organized_memory_id = ? ORDER BY raw_memory_id ASC"

    /** 来源反查：Raw -> Organized id（按 organized id 升序）。 */
    const val SELECT_ORGANIZED_BY_RAW =
        "SELECT organized_memory_id FROM organized_memory_source WHERE raw_memory_id = ? ORDER BY organized_memory_id ASC"

    /** 全局水位：空表返回 null。 */
    const val SELECT_MAX_UPDATED_AT = "SELECT MAX(updated_at) FROM organized_memory"

    const val INSERT_ORGANIZED =
        "INSERT INTO organized_memory (id, content, domains_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"

    const val UPDATE_ORGANIZED = "UPDATE organized_memory SET content = ?, domains_json = ?, updated_at = ? WHERE id = ?"

    const val INSERT_SOURCE_LINK = "INSERT INTO organized_memory_source (organized_memory_id, raw_memory_id) VALUES (?, ?)"

    /**
     * 由调用方在**同一写事务**内先经 [SELECT_MAX_UPDATED_AT] 读取全局最大值并分配严格更大的
     * 毫秒值。本语句只做单行写入，不做条件比较；传入回退值属调用方契约违规（P3 裁定）。
     */
    const val ADVANCE_UPDATED_AT = "UPDATE organized_memory SET updated_at = ? WHERE id = ?"

    const val EXISTS_PENDING_RAW = "SELECT EXISTS(SELECT 1 FROM organization_pending WHERE raw_memory_id = ?)"

    const val HAS_ANY_PENDING = "SELECT EXISTS(SELECT 1 FROM organization_pending)"

    /** 按 Raw 创建时间与 id 确定性取下一条待治理 Raw。 */
    const val SELECT_NEXT_PENDING_BY_RAW_CREATED_AT =
        "SELECT p.raw_memory_id FROM organization_pending p JOIN raw_memory r ON r.id = p.raw_memory_id " +
            "ORDER BY r.created_at ASC, r.id ASC LIMIT 1"

    /** 一次性取得全部 Pending，按 Raw 创建时间与 id 稳定排序；不得附加 limit。 */
    const val SELECT_ALL_PENDING_BY_RAW_CREATED_AT =
        "SELECT p.raw_memory_id FROM organization_pending p JOIN raw_memory r ON r.id = p.raw_memory_id " +
            "ORDER BY r.created_at ASC, r.id ASC"

    /** 重复入队幂等：已存在时影响行数为 0。 */
    const val ENQUEUE_PENDING = "INSERT OR IGNORE INTO organization_pending (raw_memory_id) VALUES (?)"

    const val DEQUEUE_PENDING = "DELETE FROM organization_pending WHERE raw_memory_id = ?"

    const val SELECT_CHECKPOINT = "SELECT last_processed_turn_id FROM dreaming_checkpoint WHERE id = 1"

    /** CAS：expected 必须匹配且 next 必须严格向前，否则影响行数为 0。 */
    const val CAS_UPDATE_CHECKPOINT =
        "UPDATE dreaming_checkpoint SET last_processed_turn_id = ? " +
            "WHERE id = 1 AND last_processed_turn_id = ? AND ? > last_processed_turn_id"

    /** 仅由集合大小生成占位符；调用方必须先处理空集合（不执行 SQL）。 */
    private fun placeholders(count: Int): String {
        require(count >= 1) { "placeholder count must be at least 1" }
        return List(count) { "?" }.joinToString(", ")
    }
}
