package com.langkraft.backend

import com.langkraft.domain.model.SubtitleLine
import java.util.UUID

/**
 * A simple SRT parser to convert raw subtitle files into Domain models.
 */
object SrtParser {
    fun parse(contentId: String, content: String): List<SubtitleLine> {
        val isVtt = content.startsWith("WEBVTT")
        val blocks = content.split(Regex("(\\r?\\n){2,}"))
        
        return blocks.mapNotNull { block ->
            val lines = block.trim().lines()
            if (lines.isEmpty()) return@mapNotNull null
            
            // For VTT, the first block might be just "WEBVTT"
            if (isVtt && lines[0].startsWith("WEBVTT")) return@mapNotNull null

            // Find line with timestamps
            val timeMatch = Regex("(\\d{2}:)?\\d{2}:\\d{2}[,. ]\\d{3} --> (\\d{2}:)?\\d{2}:\\d{2}[,. ]\\d{3}").find(block)
            val timeLine = timeMatch?.value ?: return@mapNotNull null
            
            val (startStr, endStr) = timeLine.split(" --> ").let { it[0] to it[1] }
            
            val startMs = parseTimestamp(startStr)
            val endMs = parseTimestamp(endStr)
            
            // Text is everything after the timestamp line in this block
            val timeLineIndex = lines.indexOfFirst { it.contains(" --> ") }
            val text = lines.drop(timeLineIndex + 1).joinToString(" ").trim()
            if (text.isEmpty()) return@mapNotNull null
            
            SubtitleLine(
                id = UUID.randomUUID().toString(),
                contentId = contentId,
                startMs = startMs,
                endMs = endMs,
                textDe = text,
                textEn = null
            )
        }
    }

    private fun parseTimestamp(timestamp: String): Long {
        val cleaned = timestamp.replace(",", ".").replace(" ", "").trim()
        val parts = cleaned.split(":")
        
        return when (parts.size) {
            3 -> { // HH:MM:SS.mmm
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsParts = parts[2].split(".")
                val seconds = secondsParts[0].toLong()
                val millis = secondsParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
            }
            2 -> { // MM:SS.mmm
                val minutes = parts[0].toLong()
                val secondsParts = parts[1].split(".")
                val seconds = secondsParts[0].toLong()
                val millis = secondsParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                minutes * 60000 + seconds * 1000 + millis
            }
            else -> 0L
        }
    }
}
