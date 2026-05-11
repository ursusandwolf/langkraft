package com.langkraft.io

interface FileSystem {
    fun getAppDataDir(): String
    fun exists(path: String): Boolean
    fun delete(path: String)
    fun writeBytes(path: String, bytes: ByteArray)
    fun resolve(base: String, child: String): String
}

expect class FileSystemImpl() : FileSystem {
    override fun getAppDataDir(): String
    override fun exists(path: String): Boolean
    override fun delete(path: String)
    override fun writeBytes(path: String, bytes: ByteArray)
    override fun resolve(base: String, child: String): String
}
