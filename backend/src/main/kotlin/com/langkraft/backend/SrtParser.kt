package com.langkraft.backend

import com.langkraft.domain.model.SubtitleLine
import java.util.UUID

object SrtParser {
    private val timestamp = Regex("""(\d{2,}):(\d{2}):(\d{2})[,.](\d{3})""")
    private val range = Regex("""\s*(\d{2,}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{2,}:\d{2}:\d{2}[,.]\d{3})\s*""")

    fun parse(contentId: String, srtContent: String): List<SubtitleLine> {
        val normalized = srtContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(Regex("\n\s*\n")).mapIndexed { index, block ->
            val lines = block.lines()
            require(lines.size >= 3 && lines[0].trim().toIntOrNull() != null) {
                "Invalid SRT cue ${index + 1}: expected index, time range and text"
            }
            val match = range.matchEntire(lines[1])
                ?: throw IllegalArgumentException("Invalid SRT time range in cue ${index + 1}")
            val start = parseTimestamp(match.groupValues[1])
            val end = parseTimestamp(match.groupValues[2])
            require(end > start) { "SRT cue ${index + 1} must end after it starts" }
            val text = lines.drop(2).joinToString(" ") { it.trim() }
            require(text.isNotBlank()) { "Empty SRT cue ${index + 1}" }
            SubtitleLine(UUID.randomUUID().toString(), contentId, start, end, text, null)
        }.sortedBy { it.startMs }
    }

    private fun parseTimestamp(value: String): Long {
        val match = requireNotNull(timestamp.matchEntire(value)) { "Invalid SRT timestamp" }
        val (h, m, s, ms) = match.destructured
        val hours = h.toLongOrNull() ?: throw IllegalArgumentException("Invalid SRT hours")
        require(hours <= (Long.MAX_VALUE - 3_600_000) / 3_600_000) { "SRT timestamp too large" }
        require(m.toInt() in 0..59 && s.toInt() in 0..59) { "Invalid SRT minutes or seconds" }
        return hours * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()
    }
}