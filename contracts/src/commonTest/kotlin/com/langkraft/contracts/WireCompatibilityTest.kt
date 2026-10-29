package com.langkraft.contracts

import com.langkraft.domain.ai.*
import com.langkraft.domain.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WireCompatibilityTest {
    private inline fun <reified T> assertWire(fixture: String): T {
        val decoded = Json.decodeFromString<T>(fixture)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(Json.encodeToString(decoded)))
        return decoded
    }

    @Test
    fun authRetainsStableIdentityAndFieldNames() {
        assertWire<RegisterRequest>("""{"email":"a@example.org","password":"secret","displayName":"A"}""")
        assertWire<AuthRequest>("""{"email":"a@example.org","password":"secret"}""")
        val response = assertWire<AuthResponse>("""{"token":"jwt","user":{"id":"account-a","email":"a@example.org","displayName":"A"}}""")
        assertEquals("account-a", response.user.id)
    }

    @Test
    fun syncRetainsSrsFieldsTombstonesAndCursor() {
        val fixture = """{"serverTimestamp":42,"serverChanges":[{"word":{"id":"w1","word":"Häuser","lemma":"Haus","translation":"houses","contextSentence":"Die Häuser sind alt.","contentId":"c1","subtitleLineId":"s1","addedAt":1,"status":"LEARNING","nextReviewMs":86400000,"intervalDays":1,"easeFactor":2.6,"lapseCount":2,"tags":["noun"],"lastUpdated":40},"changeType":"DELETE"}]}"""
        val response = assertWire<SyncResponse>(fixture)
        assertEquals(42L, response.serverTimestamp)
        assertEquals("DELETE", response.serverChanges.single().changeType)
        assertWire<SyncRequest>("""{"lastSyncTimestamp":42,"clientChanges":[]}""")
    }

    @Test
    fun ingestionRetainsLegacyDefaults() {
        assertWire<IngestRequest>("""{"url":"https://example.org/video"}""")
        assertWire<IngestResponse>("""{"jobId":"job-1"}""")
        val job = assertWire<IngestionJob>("""{"jobId":"job-1","status":"READY","url":"https://example.org/video","content":{"id":"c1","title":"Lesson","audioUrl":"/audio/lesson.opus","localAudioPath":null,"sourceUrl":"https://example.org/video","durationSeconds":12}}""")
        val content = assertNotNull(job.content)
        assertEquals(DownloadStatus.IDLE, content.downloadStatus)
        assertEquals(Language.DE, content.contentLanguage)
        assertEquals(Language.EN, content.translationLanguage)
        assertEquals(emptyList(), content.subtitles)
    }

    @Test
    fun aiRetainsRequestsAndStructuredResponses() {
        assertWire<TranslateRequest>("""{"word":"Haus","context":"Das Haus ist alt."}""")
        assertWire<TextRequest>("""{"text":"Das Haus ist alt."}""")
        assertWire<SentenceTranslationResult>("""{"translation":"The house is old."}""")
        assertWire<AiCapabilities>("""{"enabled":true,"provider":"ollama"}""")
        assertWire<TranslationResult>("""{"translation":"house","lemma":"Haus","partOfSpeech":"noun"}""")
        assertWire<DeepAnalysisResult>("""{"words":[{"original":"Haus","lemma":"Haus","grammaticalInfo":"Nominativ","roleInSentence":"Subject"}],"syntaxExplanation":"Subject and predicate"}""")
        assertWire<CorrectionResult>("""{"originalText":"Das Haus sind alt.","correctedText":"Das Haus ist alt.","changes":[{"original":"sind","replacement":"ist","explanation":"Singular subject"}]}""")
    }
}
