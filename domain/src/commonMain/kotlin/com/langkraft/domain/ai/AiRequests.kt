package com.langkraft.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class TranslateRequest(val word: String, val context: String)

@Serializable
data class TextRequest(val text: String)

@Serializable
data class SentenceTranslationResult(val translation: String)
