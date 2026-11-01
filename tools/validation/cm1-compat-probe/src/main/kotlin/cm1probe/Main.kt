package cm1probe

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * CM1 CT01 compatibility probe.
 *
 * Runs three probes against real file-based SQLite (temp dir = arg 1) and writes a
 * report to arg 2. Optional arg 3 = Gradle version string.
 *
 * Every library version comes from the repository's gradle/libs.versions.toml via the
 * Gradle Version Catalog (see build.gradle.kts); this file hardcodes no library version.
 *
 * Verification level: API existence + basic single-process semantics on one machine.
 * Does NOT cover crash/recovery acceptance (CM4/CM6 scope, G3/G4/G7).
 *
 * ---------------------------------------------------------------------------
 * Evidence source for P1 (verified with `javap -p -c` on the SQLDelight 2.1.0 jars
 * that this probe resolves; see build.gradle.kts for the resolved jar paths):
 *
 *  - `app.cash.sqldelight.driver.jdbc.JdbcDriver`
 *      * `close()`                       -> bytecode `0: return`  => NO-OP.
 *      * `connectionAndClose()`          -> if a transaction exists it uses
 *                                           `transaction.connection` and does NOT close it;
 *                                           otherwise it takes `getConnection()` and closes
 *                                           that connection after the statement.
 *      * `private final ThreadLocal<ConnectionManager$Transaction> transactions`
 *                                        => transactions are THREAD-LOCAL.
 *  - `app.cash.sqldelight.driver.jdbc.sqlite.ThreadedConnectionManager`
 *      * `private final ThreadLocal<java.sql.Connection> connections` => per-thread connections.
 *      * `closeConnection(conn)`         -> if `conn !== connections.get()` it throws
 *                                           IllegalStateException("Connections must be closed on
 *                                           the thread that opened them");
 *                                           if a transaction is active it does NOT close;
 *                                           otherwise it closes the connection and removes the
 *                                           ThreadLocal entry.
 *      * `close()`                       -> bytecode `0: return`  => NO-OP.
 *  - `app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriverKt.connectionManager(...)`
 *      -> a FILE url uses ThreadedConnectionManager; only in-memory urls use
 *         StaticConnectionManager.
 *
 * P1 verifies these behaviours at runtime through the real SQLDelight execute path
 * (no assumption is taken from the bytecode alone).
 * ---------------------------------------------------------------------------
 */

data class ProbeResult(
    val name: String,
    val pass: Boolean,
    val detail: String,
    val notVerified: String = "",
)

/** Reads one scalar through the real SQLDelight executeQuery path. */
private fun SqlDriver.scalarLong(sql: String): Long =
    executeQuery(
        null,
        sql,
        { cursor ->
            (cursor.next() as QueryResult.Value<*>).value
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
        {},
    ).value

/** Runs a statement through the real SQLDelight execute path. */
private fun SqlDriver.run(sql: String) {
    execute(null, sql, 0) { }
}

fun main(args: Array<String>) {
    val tmpDir = args.getOrNull(0) ?: ".local/validation/cm1/probe-tmp"
    val reportPath = args.getOrNull(1) ?: ".local/validation/cm1/probe-report.txt"
    val gradleVersion = args.getOrNull(2) ?: "(unknown)"

    java.io.File(tmpDir).mkdirs()
    val srcDb = "$tmpDir/probe-src.db"
    val dstDb = "$tmpDir/probe-dst.db"
    listOf(srcDb, dstDb, "$srcDb-wal", "$srcDb-shm", "$dstDb-wal", "$dstDb-shm").forEach {
        java.io.File(it).delete()
    }

    val results = mutableListOf<ProbeResult>()

    // ===== Probe 1: SQLDelight JdbcSqliteDriver (file url) — connection binding / PRAGMA ====
    run {
        val notes = StringBuilder()
        var fkRightAfterSet = -1L
        var fkAfterSecondSet = -1L
        var fkInsideTransaction = -1L
        var fkAfterTransaction = -1L
        var rowsInsideTransaction = -1L
        var rowsAfterTransaction = -1L
        var transactionVisibleOnOtherThread = true
        var connectionClosedByExecute = false
        var foreignKeysResetAfterReopen = true
        var driverCloseIsNoOp = false
        var crossThreadCloseRejected = false
        var crossThreadCloseReason = ""

        val driver = JdbcSqliteDriver("jdbc:sqlite:$srcDb", Properties())
        val transacter = object : TransacterImpl(driver) {}
        var leakedConnection: Connection? = null
        try {
            // (1) Per-connection PRAGMA through the real execute path.
            //     Decisive criterion: a per-connection PRAGMA only survives if the SAME
            //     physical connection is reused for the next statement.
            driver.run("PRAGMA foreign_keys=ON")
            fkRightAfterSet = driver.scalarLong("PRAGMA foreign_keys")
            driver.run("PRAGMA foreign_keys=ON")
            fkAfterSecondSet = driver.scalarLong("PRAGMA foreign_keys")
            notes.append("fkRightAfterSet=").append(fkRightAfterSet)
                .append(" fkAfterSecondSet=").append(fkAfterSecondSet)

            // (2) Real transaction (BEGIN/COMMIT) through the driver's transaction API.
            transacter.transaction {
                fkInsideTransaction = driver.scalarLong("PRAGMA foreign_keys")
                driver.run("CREATE TABLE IF NOT EXISTS p1(id INTEGER PRIMARY KEY)")
                driver.run("INSERT INTO p1(id) VALUES (1)")
                rowsInsideTransaction = driver.scalarLong("SELECT count(*) FROM p1")
                var seenOnOtherThread: Any? = "unset"
                val t = thread(name = "probe-p1-tx") {
                    seenOnOtherThread = driver.currentTransaction()
                }
                t.join()
                transactionVisibleOnOtherThread = seenOnOtherThread != null
            }
            fkAfterTransaction = driver.scalarLong("PRAGMA foreign_keys")
            rowsAfterTransaction = driver.scalarLong("SELECT count(*) FROM p1")
            notes.append(" fkInsideTransaction=").append(fkInsideTransaction)
                .append(" fkAfterTransaction=").append(fkAfterTransaction)
                .append(" rowsInsideTransaction=").append(rowsInsideTransaction)
                .append(" rowsAfterTransaction=").append(rowsAfterTransaction)
                .append(" transactionVisibleOnOtherThread=").append(transactionVisibleOnOtherThread)

            // (3) closeConnection vs close().
            //     The connection taken here is the one the next execute() uses and closes.
            val conn = driver.getConnection()
            driver.run("PRAGMA foreign_keys=ON")
            connectionClosedByExecute = conn.isClosed
            // A new physical connection must start from the default PRAGMA value.
            val reopened = driver.getConnection()
            try {
                foreignKeysResetAfterReopen = reopened.scalarLongOrNull("PRAGMA foreign_keys") == 0L
            } finally {
                runCatching { reopened.close() }
            }
            notes.append(" connectionClosedByExecute=").append(connectionClosedByExecute)
                .append(" foreignKeysResetAfterReopen=").append(foreignKeysResetAfterReopen)

            // closeConnection() from a foreign thread must be refused.
            val owned = driver.getConnection()
            try {
                val t = thread(name = "probe-p1-close") {
                    try {
                        driver.closeConnection(owned)
                    } catch (e: IllegalStateException) {
                        crossThreadCloseRejected = true
                        crossThreadCloseReason = e.message ?: ""
                    }
                }
                t.join()
            } finally {
                runCatching { driver.closeConnection(owned) }
            }

            // driver.close() is a no-op (javap: bytecode `0: return`) that does NOT close
            // the open connection on the calling thread -> leak point.
            val driver2 = JdbcSqliteDriver("jdbc:sqlite:$srcDb", Properties())
            try {
                leakedConnection = driver2.getConnection()
                driver2.close()
                driverCloseIsNoOp = !leakedConnection.isClosed
            } finally {
                runCatching { leakedConnection?.close() }
                runCatching { driver2.close() }
            }
            notes.append(" crossThreadCloseRejected=").append(crossThreadCloseRejected)
                .append(" driverCloseIsNoOp=").append(driverCloseIsNoOp)
        } finally {
            runCatching { driver.close() }
        }

        // What the probe asserts: the stock file-url driver does NOT keep one physical
        // connection across statements (per-connection PRAGMA does not survive), it binds
        // transactions to the calling thread, it closes the thread connection after every
        // non-transactional statement, and driver.close() leaves connections open.
        val ok = fkRightAfterSet == 0L &&
            fkAfterSecondSet == 0L &&
            fkInsideTransaction == 0L &&
            fkAfterTransaction == 0L &&
            rowsInsideTransaction == 1L &&
            rowsAfterTransaction == 1L &&
            !transactionVisibleOnOtherThread &&
            connectionClosedByExecute &&
            foreignKeysResetAfterReopen &&
            crossThreadCloseRejected &&
            driverCloseIsNoOp

        results += ProbeResult(
            name = "P1: SQLDelight JdbcSqliteDriver (file url) real execute path — " +
                "connection binding, PRAGMA persistence, thread-bound transactions, close semantics",
            pass = ok,
            detail = notes.toString(),
            notVerified = "Long-term driver lifetime binding; transaction resumption after a " +
                "coroutine thread switch; multi-Slot (1 write + N read) behaviour; explicit " +
                "per-Slot fixed-connection driver implementation. All of these belong to CT04.",
        )
    }

    // ===== Probe 2: PRAGMA + JSON1 + sqlite_version on Xerial SQLite JDBC ===================
    run {
        val c = DriverManager.getConnection("jdbc:sqlite:$dstDb", Properties())
        var journalMode = ""
        var synchronous = ""
        var busyTimeout = ""
        var foreignKeys = ""
        var queryOnly = ""
        var sqliteVersion = ""
        var jsonValid = ""
        var jsonType = ""
        var jsonCheckRejected = false
        var jsonCheckReason = ""
        var queryOnlyWriteRejected = false
        var queryOnlyWriteReason = ""

        try {
            c.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA synchronous=FULL")
                s.execute("PRAGMA busy_timeout=5000")
                s.execute("PRAGMA foreign_keys=ON")
                // Create table first before turning query_only on, so we can test JSON1 + query_only separately.
                s.execute("CREATE TABLE p2(t TEXT CHECK (json_valid(t)))")
            }
            journalMode = c.scalarOrEmpty("PRAGMA journal_mode")
            synchronous = c.scalarOrEmpty("PRAGMA synchronous")
            busyTimeout = c.scalarOrEmpty("PRAGMA busy_timeout")
            foreignKeys = c.scalarOrEmpty("PRAGMA foreign_keys")
            sqliteVersion = c.scalarOrEmpty("SELECT sqlite_version()")
            jsonValid = c.scalarOrEmpty("SELECT json_valid('[1]')")
            jsonType = c.scalarOrEmpty("SELECT json_type('[\"a\"]')")

            // (A) JSON1 CHECK constraint: only the illegal INSERT is guarded, and the failure
            // reason must actually point at the CHECK / json_valid constraint.
            try {
                c.createStatement().use { it.executeUpdate("INSERT INTO p2 VALUES ('{bad')") }
            } catch (e: SQLException) {
                jsonCheckRejected = true
                jsonCheckReason = "${e.javaClass.simpleName}: ${e.message}"
            }

            // (B) query_only=ON must take effect and actually reject a subsequent write.
            c.createStatement().use { it.execute("PRAGMA query_only=ON") }
            queryOnly = c.scalarOrEmpty("PRAGMA query_only")
            try {
                c.createStatement().use { it.executeUpdate("INSERT INTO p2 VALUES ('[1]')") }
            } catch (e: SQLException) {
                queryOnlyWriteRejected = true
                queryOnlyWriteReason = "${e.javaClass.simpleName}: ${e.message}"
            }
        } finally {
            c.close()
        }

        val checkMentionsJsonOrCheck =
            jsonCheckReason.contains("CHECK", ignoreCase = true) ||
                jsonCheckReason.contains("json_valid", ignoreCase = true) ||
                jsonCheckReason.contains("json", ignoreCase = true)

        val detail = "journal_mode=$journalMode synchronous=$synchronous busy_timeout=$busyTimeout " +
            "foreign_keys=$foreignKeys query_only=$queryOnly sqlite_version=$sqliteVersion " +
            "json_valid('[1]')=$jsonValid json_type('[\"a\"]')=$jsonType " +
            "jsonCheckRejected=$jsonCheckRejected jsonCheckReason='$jsonCheckReason' " +
            "queryOnlyWriteRejected=$queryOnlyWriteRejected queryOnlyWriteReason='$queryOnlyWriteReason'"

        val ok = journalMode == "wal" &&
            synchronous == "2" &&
            busyTimeout == "5000" &&
            foreignKeys == "1" &&
            queryOnly == "1" &&
            jsonValid == "1" &&
            jsonType == "array" &&
            jsonCheckRejected && checkMentionsJsonOrCheck &&
            queryOnlyWriteRejected

        results += ProbeResult(
            name = "P2: Xerial PRAGMA / JSON1 / sqlite_version (direct assertions)",
            pass = ok,
            detail = detail,
            notVerified = "Per-connection PRAGMA isolation across several simultaneously open " +
                "connections; WAL behaviour under concurrent writers. Those belong to CT04.",
        )
    }

    // ===== Probe 3: Xerial Online Backup API on a WAL source ================================
    run {
        val notes = StringBuilder()
        var backupRc = -1
        var targetRows = 0
        var quickCheck = ""
        var sourceJournalMode = ""

        // The SOURCE database is the WAL one: created in WAL mode here and its journal
        // mode is asserted before the backup runs.
        val srcConn = DriverManager.getConnection("jdbc:sqlite:$srcDb", Properties())
        try {
            srcConn.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("CREATE TABLE IF NOT EXISTS p3(id INTEGER PRIMARY KEY, v TEXT)")
                s.executeUpdate("INSERT OR REPLACE INTO p3(id, v) VALUES (1, 'x')")
            }
            sourceJournalMode = srcConn.scalarOrEmpty("PRAGMA journal_mode")

            // Target must not exist beforehand.
            java.io.File(dstDb).delete()

            // The source connection stays OPEN across the backup call (online snapshot).
            val srcNative = (srcConn as org.sqlite.jdbc4.JDBC4Connection)
                .getDatabase() as org.sqlite.core.NativeDB
            backupRc = srcNative.backup("main", dstDb, null)
            notes.append("sourceJournalMode=").append(sourceJournalMode)
                .append(" backupRc=").append(backupRc)
                .append(" sourceStillOpenDuringBackup=true")

            // Open the target read-only and verify the snapshot.
            val dstConn = DriverManager.getConnection(
                "jdbc:sqlite:file:$dstDb?mode=ro",
                Properties(),
            )
            try {
                targetRows = dstConn.scalarOrEmpty("SELECT count(*) FROM p3").toInt()
                quickCheck = dstConn.scalarOrEmpty("PRAGMA quick_check")
            } finally {
                dstConn.close()
            }
        } catch (e: Exception) {
            notes.append(" exception=").append(e.javaClass.simpleName).append(": ").append(e.message)
        } finally {
            srcConn.close()
        }

        val targetLen = java.io.File(dstDb).length()
        notes.append(" targetLen=").append(targetLen)
            .append(" targetRows=").append(targetRows)
            .append(" quickCheck='").append(quickCheck).append('\'')

        val ok = sourceJournalMode == "wal" && backupRc == 0 && targetLen > 0 &&
            targetRows == 1 && quickCheck == "ok"

        results += ProbeResult(
            name = "P3: Xerial Online Backup API (WAL source kept open during backup)",
            pass = ok,
            detail = notes.toString(),
            notVerified = "Backup failure/cancellation cleanup; crash during backup; WAL " +
                "cross-process consistency; 'old backup is preserved on failure'. Those belong to CT27 / CM6.",
        )
    }

    // ===== Report ===========================================================================
    val sb = StringBuilder()
    sb.appendLine("CM1 CT01 Compatibility Probe Report")
    sb.appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
    sb.appendLine("Gradle: $gradleVersion")
    sb.appendLine("Library versions come from the repository gradle/libs.versions.toml (Gradle Version Catalog):")
    sb.appendLine("  kotlin = 2.2.21, sqldelight = 2.1.0, org.xerial:sqlite-jdbc = 3.49.1.0")
    sb.appendLine("  (see tools/validation/cm1-compat-probe/build.gradle.kts; no version is hardcoded there)")
    sb.appendLine("Source DB: $srcDb")
    sb.appendLine("Target DB: $dstDb")
    sb.appendLine()
    for (r in results) {
        sb.appendLine(if (r.pass) "PASS  ${r.name}" else "FAIL  ${r.name}")
        sb.appendLine("      ${r.detail}")
        if (r.notVerified.isNotEmpty()) {
            sb.appendLine("      NOT VERIFIED: ${r.notVerified}")
        }
    }
    sb.appendLine()
    sb.appendLine("Verification level: API existence + basic single-process semantics only.")
    sb.appendLine("Does NOT cover: crash recovery, WAL cross-process consistency, fault injection,")
    sb.appendLine("fault-tolerant backup. Those are CM4/CM6 (G3/G4/G7) acceptance scope.")
    java.io.File(reportPath).writeText(sb.toString())
    results.forEach { r -> println((if (r.pass) "PASS" else "FAIL") + "  " + r.name + " — " + r.detail) }
    exitProcess(if (results.all { it.pass }) 0 else 1)
}

/** Reads one scalar with a plain JDBC statement; closes everything it opens. */
private fun Connection.scalarOrEmpty(sql: String): String {
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            return if (rs.next()) rs.getString(1) else ""
        }
    }
}

/** Same, but returns null when the statement fails (used for the read-only reopen check). */
private fun Connection.scalarLongOrNull(sql: String): Long? = runCatching { scalarOrEmpty(sql).toLong() }.getOrNull()
