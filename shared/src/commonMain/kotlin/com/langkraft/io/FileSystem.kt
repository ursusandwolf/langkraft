package com.langkraft.io

interface FileSystem {
    fun getAppDataDir(): String
    fun exists(path: String): Boolean
    fun delete(path: String)
    fun writeBytes(path: String, bytes: ByteArray)
}

expect class FileSystemImpl() : FileSystem {
    override fun getAppDataDir(): String
    override fun exists(path: String): Boolean
    override fun delete(path: String)
    override fun writeBytes(path: String, bytes: ByteArray)
}
