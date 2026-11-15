package com.github.noahshen.alcyone.context.memory.server

import com.github.noahshen.alcyone.context.memory.runtime.ContextConfigLoader
import com.github.noahshen.alcyone.context.vfs.VfsUri
import com.github.noahshen.alcyone.context.vfs.runtime.AlcyoneVfs
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Test fixture only: uses the public VFS SDK to prepare a real resource before launching Standalone. */
object StandaloneResourceFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            val config = ContextConfigLoader.load(Path.of(args[0])).vfsConfig
            val vfs = AlcyoneVfs.create(config)
            try {
                vfs.write(VfsUri.parse("alcyone://resources/slow.bin"), ByteArray(4 * 1024 * 1024) { (it % 251).toByte() })
            } finally {
                vfs.close()
            }
        }
    }
}
