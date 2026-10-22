package com.langkraft.io

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    actual override fun resolve(base: String, child: String): String {
        return File(base, child).absolutePath
    }

    actual override fun rename(from: String, to: String) {
        try {
            val source = File(from).toPath()
            val target = File(to).toPath()
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Fallback for filesystems that don't support atomic move
            try {
                Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e2: Exception) {
                // Last resort
                File(from).renameTo(File(to))
            }
        }
    }
}
