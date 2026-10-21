package com.langkraft.backend

import com.langkraft.domain.model.SubtitleLine
import java.util.UUID

class SubtitleParseException(message: String) : IllegalArgumentException(message)

/** Rejects the entire file on an invalid cue; overlaps are preserved in stable start-time order. */
object SrtParser {
    private const val TIMESTAMP = "([0-9]{2,}):([0-5][0-9]):([0-5][0-9])[,\\.]([0-9]{3})"
    private val timeRange = Regex("$TIMESTAMP[ \\t]*-->[ \\t]*$TIMESTAMP")
    private val indexPattern = Regex("[0-9]+")

    fun parse(contentId: String, srtContent: String): List<SubtitleLine> {
        val lines = srtContent.removePrefix("\uFEFF").lines()
        val cues = mutableListOf<SubtitleLine>()
        var previousIndex = 0L
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }

            val indexLine = i + 1
            val index = line.takeIf { indexPattern.matches(it) }?.toLongOrNull()
                ?: invalid(indexLine, "Expected a positive cue index")
            if (index <= previousIndex) invalid(indexLine, "Cue indices must be positive and strictly increasing")
            previousIndex = index

            i++
            if (i >= lines.size) invalid(indexLine, "Missing time range")
            val timestampLine = i + 1
            val range = timeRange.matchEntire(lines[i].trim())
                ?: invalid(timestampLine, "Expected HH:MM:SS,mmm --> HH:MM:SS,mmm")
            val startMs = parseTimestamp(range.groupValues.subList(1, 5), timestampLine)
            val endMs = parseTimestamp(range.groupValues.subList(5, 9), timestampLine)
            if (endMs <= startMs) invalid(timestampLine, "Cue end must be later than its start")

            i++
            val text = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                val part = lines[i].trim()
                if (timeRange.matches(part)) invalid(i + 1, "Missing blank separator between cues")
                text += part
                i++
            }
            if (text.isEmpty()) invalid(indexLine, "Missing cue text")

            cues.add(SubtitleLine(
                id = UUID.randomUUID().toString(),
                contentId = contentId,
                startMs = startMs,
                endMs = endMs,
                textDe = text.joinToString(" "),
                textEn = null
            ))
        }
        if (cues.isEmpty()) invalid(1, "Subtitle file is empty")
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestamp(parts: List<String>, line: Int): Long {
        val hours = parts[0].toLongOrNull() ?: invalid(line, "Timestamp exceeds the supported range")
        val remainder = parts[1].toLong() * 60_000 + parts[2].toLong() * 1000 + parts[3].toLong()
        if (hours > (Long.MAX_VALUE - remainder) / 3_600_000) {
            invalid(line, "Timestamp exceeds the supported range")
        }
        return hours * 3_600_000 + remainder
    }

    private fun invalid(line: Int, reason: String): Nothing =
        throw SubtitleParseException("Invalid SRT at line $line: $reason")
}
