package com.langkraft.backend

sealed class LangkraftException(message: String, cause: Throwable? = null) : Exception(message, cause)

class IngestionException(message: String, cause: Throwable? = null) : LangkraftException(message, cause)

class AiException(message: String, cause: Throwable? = null) : LangkraftException(message, cause)

class AiUnavailableException : LangkraftException("AI is disabled; enter translations manually")

class IngestionBusyException : LangkraftException("Import capacity reached; try again later")

class MediaCapacityException : LangkraftException("Media storage capacity reached; try again later")

class MediaLimitException(message: String = "Import exceeds configured media byte limit") : LangkraftException(message)
