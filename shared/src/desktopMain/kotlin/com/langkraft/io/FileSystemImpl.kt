package com.langkraft.io

import java.io.File

actual class FileSystemImpl : FileSystem {
    actual override fun getAppDataDir(): String {
        val home = System.getProperty("user.home")
        val dir = File(home, ".langkraft")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual override fun exists(path: String): Boolean {
        return File(path).exists()
    }

    actual override fun delete(path: String) {
        File(path).delete()
    }

    actual override fun writeBytes(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
    }
}
