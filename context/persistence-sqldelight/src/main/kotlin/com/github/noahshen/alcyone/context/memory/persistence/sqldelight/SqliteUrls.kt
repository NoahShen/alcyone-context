package com.github.noahshen.alcyone.context.memory.persistence.sqldelight

import java.nio.file.Path

/**
 * 连接 URL 的唯一来源。
 *
 * Xerial 对 `jdbc:sqlite:<path>` 直接拼接路径时，空格、`%`、`?`、`#` 这类字符会被
 * 当作 URL 语法解析而打开失败（Architect R4 探针实测）。统一用 [Path.toUri] 生成
 * 编码正确的文件 URI：
 *
 * - [openReadUrl]：`?mode=ro`，**不存在 / 不是数据库文件一律拒绝，绝不创建**；
 *   0 字节空文件可以以只读方式打开（user_version=0），由调用方按「未初始化」处理。
 * - [openReadWriteUrl]：`?mode=rw`，库缺失时拒绝而不是自动建空库（Xerial 默认行为
 *   是缺库创建），这是普通打开不得静默建库的最后防线；校验后文件消失也在这里被拒。
 * - [openCreateUrl]：仅显式初始化使用，创建者已通过 [java.nio.channels.FileChannel] 的
 *   `CREATE_NEW` 原子认领了该文件，URI 只做定位；连接必须以 `mode=rw` 打开，不允许
 *   认领后丢文件再次建库。
 */
internal object SqliteUrls {
    fun openReadUrl(path: Path): String = "jdbc:sqlite:${path.toUri()}?mode=ro"

    /** 读写连接：库缺失时拒绝而不是自动建库。 */
    fun openReadWriteUrl(path: Path): String = "jdbc:sqlite:${path.toUri()}?mode=rw"

    /** 仅显式初始化（已原子认领文件）使用，以 mode=rw 打开已认领文件。 */
    fun openCreateUrl(path: Path): String = "jdbc:sqlite:${path.toUri()}?mode=rw"
}
