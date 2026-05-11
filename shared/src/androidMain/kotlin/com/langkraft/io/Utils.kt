package com.langkraft.io

import java.util.UUID

actual fun randomUUID(): String = UUID.randomUUID().toString()
