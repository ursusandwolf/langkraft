package com.langkraft.backend.ai

import com.langkraft.backend.AiException
import com.langkraft.domain.ai.*
import kotlinx.serialization.json.Json

/** Shared language tasks; the strategy handles only the selected provider protocol. */
class StrategyLinguisticAssistant(
    private val strategy: TextGenerationStrategy,
    private val provider: String
) : LinguisticAssistant {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun capabilities() = AiCapabilities(true, provider)

    override suspend fun translateWord(word: String, context: String): TranslationResult {
        val prompt = """
            Translate the German word "$word" in the context of this sentence: "$context".
            Provide the result as a JSON object with fields:
            "translation" (string), "lemma" (base form, string), "partOfSpeech" (string), "explanation" (string, optional).
            Output ONLY the JSON object.
        """.trimIndent()

        return callStructured(prompt)
    }

    override suspend fun translateSentence(text: String): String {
        val prompt = "Translate this German sentence to English: \"${text}\". Output only the translation."
        return strategy.generate(prompt).trim()
    }

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult {
        val prompt = """
            Perform a professional grammatical analysis of this German sentence: "${text}".
            Focus on the Ilis Immersion methodology (Deep Analysis).
            Provide the result as a JSON object with fields:
            "words": list of objects with:
                "original": the word as it appears in the text,
                "lemma": the dictionary form (e.g., "gegangen" -> "gehen"),
                "grammaticalInfo": detailed info (e.g., "Noun, feminine, Genitiv, singular" or "Verb, 1st person singular, Präsens, Indikativ"),
                "roleInSentence": (e.g., "Subject", "Indirect Object", "Predicate part"),
            "syntaxExplanation": A clear explanation of the sentence structure, word order (V2, end-placed verb, etc.), and any tricky idiomatic parts.

            Output ONLY the JSON object.
        """.trimIndent()

        return callStructured(prompt)
    }

    override suspend fun correctText(text: String): CorrectionResult {
        val prompt = """
            Correct this German text as a supportive language teacher: "${text}".
            Focus on the Ilis Immersion methodology (natural phrasing and pedagogical clarity).

            Provide the result as a JSON object with fields:
            "originalText": the input text,
            "correctedText": the improved version, making it sound more natural and idiomatic while keeping the user's intended meaning,
            "changes": list of objects with:
                "original": the part being changed,
                "replacement": the correction,
                "explanation": a short, encouraging pedagogical explanation (e.g., explaining why a certain case was used or why the word order changed).

            Focus especially on:
            - Word order (V2, end-placed verbs in subordinate clauses).
            - Correct case usage (Nominativ, Akkusativ, Dativ, Genitiv).
            - Natural-sounding vocabulary (idiomatic German).

            Output ONLY the JSON object.
        """.trimIndent()

        return callStructured(prompt)
    }

    private suspend inline fun <reified T> callStructured(prompt: String): T {
        val text = strategy.generate(prompt).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { json.decodeFromString<T>(text) }
        catch (failure: Exception) { throw AiException("AI provider returned an invalid structured response") }
    }
}
