package com.langkraft.io

actual class FileSystemImpl : FileSystem {
    actual override fun getAppDataDir(): String = "tmp"
    actual override fun exists(path: String): Boolean = false
    actual override fun delete(path: String) {}
    actual override fun writeBytes(path: String, bytes: ByteArray) {}
    actual override fun resolve(base: String, child: String): String = "$base/$child"
    actual override fun rename(from: String, to: String) {}
}
