package com.github.noahshen.alcyone.context.vfs

import java.security.SecureRandom

/** Implementation detail shared by Core and Storage; never part of a logical path. */
object PublishTempName {
    private val random = SecureRandom()

    fun newName(): String = ".alcyone-publish-" + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } + ".tmp"
}
