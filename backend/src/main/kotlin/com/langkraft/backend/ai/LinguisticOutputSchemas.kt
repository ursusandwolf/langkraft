package com.langkraft.backend.ai

import kotlinx.serialization.json.*

/** Schemas belong to language tasks, not to a provider's HTTP adapter. */
internal object LinguisticOutputSchemas {
    private val text = buildJsonObject { put("type", "string") }
    private fun fields(vararg names: String) = names.associateWith { text }
    private fun objectSchema(properties: Map<String, JsonElement>, required: List<String> = properties.keys.toList()) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
    private fun arrayOf(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }

    val translation = objectSchema(fields("translation", "lemma", "partOfSpeech") +
        ("explanation" to buildJsonObject { putJsonArray("type") { add("string"); add("null") } }),
        listOf("translation", "lemma", "partOfSpeech"))
    val analysis = objectSchema(mapOf(
        "words" to arrayOf(objectSchema(fields("original", "lemma", "grammaticalInfo", "roleInSentence"))),
        "syntaxExplanation" to text
    ))
    val correction = objectSchema(fields("originalText", "correctedText") +
        ("changes" to arrayOf(objectSchema(fields("original", "replacement", "explanation")))))
}
