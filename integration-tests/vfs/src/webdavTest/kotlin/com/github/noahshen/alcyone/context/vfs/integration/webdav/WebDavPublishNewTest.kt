package com.github.noahshen.alcyone.context.vfs.integration.webdav

import com.github.noahshen.alcyone.context.vfs.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** WsgiDAV rejects the conditional MOVE contract; capability must remain conservatively disabled. */
class WebDavPublishNewTest {
    @TempDir lateinit var temp: Path

    @Test
    @Timeout(120)
    fun `unsupported publication cannot write or overwrite`() =
        runBlocking {
            WebDavFixture.withVfs(WebDavFixture.remoteOnlyConfig(temp.resolve("state.sqlite"))) { vfs ->
                val target = VfsUri.parse("alcyone://resources/remote/publish-new/snapshot.sqlite")
                val first = "complete SQLite bytes".toByteArray()
                vfs.write(target, first, WriteOptions(mode = WriteMode.CREATE_NEW))
                val input =
                    object : ByteArrayInputStream("replacement".toByteArray()) {
                        var closed = false

                        override fun close() {
                            closed = true
                            super.close()
                        }
                    }
                val error = assertFailsWith<VfsException> { vfs.publishNew(target, input, PublishOptions(11, 11)) }
                assertEquals(VfsErrorCode.UNSUPPORTED_OPERATION, error.code)
                assertEquals(true, input.closed)
                assertContentEquals(first, vfs.read(target))
            }
        }
}
