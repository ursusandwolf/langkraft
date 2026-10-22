package com.langkraft.io

actual fun randomUUID(): String {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(Regex("[xy]")) {
        val r = (kotlin.random.Random.nextDouble() * 16).toInt()
        val v = if (it.value == "x") r else (r and 0x3 or 0x8)
        v.toString(16)
    }
}
