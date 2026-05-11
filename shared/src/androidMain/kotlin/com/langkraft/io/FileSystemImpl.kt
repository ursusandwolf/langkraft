package com.langkraft.io

import android.content.Context
import java.io.File

actual class FileSystemImpl(private val context: Context) : FileSystem {
    actual override fun getAppDataDir(): String {
        return context.filesDir.absolutePath
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

    actual override fun resolve(base: String, child: String): String {
        return File(base, child).absolutePath
    }
    }
