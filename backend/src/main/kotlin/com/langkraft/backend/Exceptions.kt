package com.langkraft.backend

sealed class LangkraftException(message: String, cause: Throwable? = null) : Exception(message, cause)

class IngestionException(message: String, cause: Throwable? = null) : LangkraftException(message, cause)

class AiException(message: String, cause: Throwable? = null) : LangkraftException(message, cause)
