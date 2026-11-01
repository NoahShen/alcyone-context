package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import com.github.noahshen.alcyone.context.common.TimeCodec
import java.time.Instant

/**
 * SQLite 时间列的写入 / 读取边界。
 *
 * 技术实现设计 §2.4 规定：所有时间文本统一保存上海本地时间，固定毫秒精度、
 * 固定 `+08:00` 偏移，格式 `uuuu-MM-dd'T'HH:mm:ss.SSS+08:00`。
 * 算法本身在 `common` 的 [TimeCodec]（用户授权迁移的公共能力），
 * 本模块只做「数据库边界」的封装，避免各 Repository 各自拼时间字符串。
 *
 * 直接打开 SQLite 读到的必须是这个原文形态，不能是 `...Z`。
 */
internal object ContextDbTime {
    /** 把绝对时刻编码为数据库时间文本。 */
    fun encode(instant: Instant): String = TimeCodec.format(instant)

    /** 严格解析数据库时间文本；非规范文本直接拒绝。 */
    fun decode(text: String): Instant = TimeCodec.parse(text)
}
