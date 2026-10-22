package com.langkraft.io

expect fun randomUUID(): String

object Logger {
    fun d(message: String) = log("DEBUG", message)
    fun i(message: String) = log("INFO", message)
    fun w(message: String) = log("WARN", message)
    fun e(message: String, throwable: Throwable? = null) = log("ERROR", "$message ${throwable?.message ?: ""}")

    private fun log(level: String, message: String) {
        println("[$level] Langkraft: $message")
    }
}
