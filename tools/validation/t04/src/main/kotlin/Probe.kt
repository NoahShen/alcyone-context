// Isolated dependency probe, not a VFS implementation or its final Schema.
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.f4b6a3.uuid.UuidCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.opendal.Operator
import org.apache.opendal.OpenDALException
import org.slf4j.LoggerFactory
import probe.ProbeDatabase
import java.nio.file.Files
import java.util.Properties

@Serializable data class Sample(val text: String)

fun missing(op: Operator, path: String) {
    val failure = runCatching { op.stat(path) }.exceptionOrNull()
    check(failure is OpenDALException && failure.code.toString() == "NotFound") { "$path: $failure" }
}

fun storage(op: Operator, label: String) {
    val c = op.info.capability
    println("CAP $label read=${c.read} write=${c.write} stat=${c.stat} list=${c.list} " +
        "mkdir=${c.createDir} delete=${c.delete} rename=${c.rename} recursiveList=${c.listWithRecursive}")
    check(c.read && c.write && c.stat && c.list && c.createDir && c.delete)
    op.createDir("probe/empty/")
    val content = byteArrayOf(0, 1, 127, -1)
    op.write("probe/a.bin", content)
    check(op.read("probe/a.bin").contentEquals(content))
    op.createInputStream("probe/a.bin").use { input ->
        val prefix = input.readNBytes(3) // limit=2: one extra byte detects overflow
        check(prefix.size == 3 && prefix.contentEquals(content.copyOf(3)))
    }
    check(op.stat("probe/a.bin").contentLength == content.size.toLong())
    check(op.stat("probe/empty/").isDir)
    check(op.list("probe/").map { it.path }.containsAll(listOf("probe/a.bin", "probe/empty/")))
    op.write("probe/a.bin", byteArrayOf(9))
    check(op.read("probe/a.bin").contentEquals(byteArrayOf(9)))
    if (c.rename) {
        op.rename("probe/a.bin", "probe/b.bin")
        missing(op, "probe/a.bin")
        check(op.read("probe/b.bin").contentEquals(byteArrayOf(9)))
        op.delete("probe/b.bin")
    } else op.delete("probe/a.bin")
    op.delete("probe/empty/")
    op.delete("probe/")
    missing(op, "probe/")
    println("PASS $label binary write/read/bounded-stream/overwrite/stat/list/empty-dir/delete/native-file-rename-if-supported")
    op.createDir("tree/empty/")
    op.write("tree/item.bin", content)
    val renameFailure = if (c.rename) runCatching { op.rename("tree/", "moved/") }.exceptionOrNull() else null
    if (c.rename && renameFailure == null) {
        check(op.read("moved/item.bin").contentEquals(content))
        check(op.stat("moved/empty/").isDir)
        missing(op, "tree/")
        println("OBSERVE $label native-directory-rename=passed")
    } else {
        check(op.read("tree/item.bin").contentEquals(content))
        println("OBSERVE $label native-directory-rename=${(renameFailure as? OpenDALException)?.code ?: "unsupported"}")
    }
    op.removeAll("tree/")
    op.removeAll("moved/")
    val missingDelete = runCatching { op.delete("absent.bin") }.exceptionOrNull()
    println("OBSERVE $label delete-missing=${(missingDelete as? OpenDALException)?.code ?: if (missingDelete == null) "success" else "other-error"}")
}

fun database() {
    val dbFile = Files.createTempFile("t04-", ".db")
    try {
        JdbcSqliteDriver("jdbc:sqlite:$dbFile", Properties(), ProbeDatabase.Schema).use { driver ->
            val db = ProbeDatabase(driver)
            db.transaction {
                db.stateQueries.insertNode("n1", "/a")
                db.stateQueries.insertEvent("e1", "n1")
            }
            val failure = runCatching {
                db.transaction {
                    db.stateQueries.insertNode("n2", "/b")
                    db.stateQueries.insertEvent("e1", "n2") // duplicate event forces rollback
                }
            }.exceptionOrNull()
            check(failure != null)
            check(db.stateQueries.nodeCount().executeAsOne() == 1L)
            check(db.stateQueries.eventCount().executeAsOne() == 1L)
            check(runCatching { db.stateQueries.insertNode("n3", "/a") }.isFailure)
        }
        JdbcSqliteDriver("jdbc:sqlite:$dbFile").use { driver ->
            val db = ProbeDatabase(driver)
            check(db.stateQueries.nodeCount().executeAsOne() == 1L)
            check(db.stateQueries.eventCount().executeAsOne() == 1L)
        }
        println("PASS SQLDelight generation/native SQLite/unique-path/atomic rollback/close-reopen")
    } finally { Files.deleteIfExists(dbFile) }
}

fun main() = runBlocking {
    withContext(Dispatchers.IO) {
        val root = Files.createTempDirectory("t04-fs-")
        val outside = Files.createTempDirectory("t04-outside-")
        try {
            Operator.of("fs", mapOf("root" to root.toString())).use { fs ->
                storage(fs, "LocalFS")
                Files.writeString(outside.resolve("secret.txt"), "outside-probe")
                Files.createSymbolicLink(root.resolve("link"), outside)
                val follows = runCatching { String(fs.read("link/secret.txt")) == "outside-probe" }.getOrDefault(false)
                println("OBSERVE LocalFS follows-symlink-outside-root=$follows (VFS must enforce T02 separately)")
                Files.delete(root.resolve("link"))
                val endpoint = requireNotNull(System.getenv("T04_WEBDAV_ENDPOINT")) { "Start the temporary WebDAV fixture first" }
                Operator.of("webdav", mapOf("endpoint" to endpoint, "root" to "/")).use { dav ->
                    storage(dav, "WebDAV")
                    fs.write("cross.bin", byteArrayOf(3, 4, 5))
                    dav.write("cross.bin", fs.read("cross.bin"))
                    check(dav.read("cross.bin").contentEquals(fs.read("cross.bin")))
                    fs.delete("cross.bin")
                    missing(fs, "cross.bin")
                    fs.write("back.bin", dav.read("cross.bin"))
                    check(fs.read("back.bin").contentEquals(dav.read("cross.bin")))
                    dav.delete("cross.bin")
                    missing(dav, "cross.bin")
                    fs.delete("back.bin")
                    println("PASS LocalFS-WebDAV bidirectional copy-confirm-delete (storage only)")
                }
            }
            database()
            val ids = List(10_000) { UuidCreator.getTimeOrderedEpoch() }
            check(ids.all { it.version() == 7 && it.variant() == 2 })
            check(ids.toSet().size == ids.size)
            val sample = Sample("中文")
            check(Json.decodeFromString<Sample>(Json.encodeToString(sample)) == sample)
            LoggerFactory.getLogger("t04").info("PASS UUIDv7 sample uniqueness/version; coroutines; serialization; SLF4J")
        } finally {
            // These roots are created exclusively by this probe; never point them at user files.
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
