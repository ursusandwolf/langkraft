package com.langkraft.backend

import com.langkraft.domain.model.SubtitleLine
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * A simple SRT parser to convert raw subtitle files into Domain models.
 */
object SrtParser {
    private val logger = LoggerFactory.getLogger(SrtParser::class.java)
    private val BLOCK_SEPARATOR = Regex("(\\r?\\n){2,}")
    private val TIMESTAMP_PATTERN = Regex("(\\d{2}:)?\\d{2}:\\d{2}[,. ]\\d{3} --> (\\d{2}:)?\\d{2}:\\d{2}[,. ]\\d{3}")

    fun parse(contentId: String, content: String): List<SubtitleLine> {
        val isVtt = content.startsWith("WEBVTT")
        val blocks = content.split(BLOCK_SEPARATOR)
        
        return blocks.mapNotNull { block ->
            val lines = block.trim().lines()
            if (lines.isEmpty()) return@mapNotNull null
            
            // For VTT, the first block might be just "WEBVTT"
            if (isVtt && lines[0].startsWith("WEBVTT")) return@mapNotNull null

            // Find line with timestamps
            val timeMatch = TIMESTAMP_PATTERN.find(block)
            val timeLine = timeMatch?.value ?: return@mapNotNull null
            
            val (startStr, endStr) = timeLine.split(" --> ").let { it[0] to it[1] }
            
            val startMs = parseTimestamp(startStr) ?: return@mapNotNull null
            val endMs = parseTimestamp(endStr) ?: return@mapNotNull null
            
            // Text is everything after the timestamp line in this block
            val timeLineIndex = lines.indexOfFirst { it.contains(" --> ") }
            val text = lines.drop(timeLineIndex + 1).joinToString(" ").trim()
            if (text.isEmpty()) return@mapNotNull null
            
            SubtitleLine(
                id = "${contentId}_${startMs}_${endMs}",
                contentId = contentId,
                startMs = startMs,
                endMs = endMs,
                textDe = text,
                textEn = null
            )
        }
    }

    private fun parseTimestamp(timestamp: String): Long? {
        val cleaned = timestamp.replace(",", ".").replace(" ", "").trim()
        val parts = cleaned.split(":")
        
        return try {
            when (parts.size) {
                3 -> { // HH:MM:SS.mmm
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secondsWithMillis = parts[2].split(".")
                    val seconds = secondsWithMillis[0].toLong()
                    val millis = parseMillis(secondsWithMillis.getOrNull(1))
                    
                    hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis
                }
                2 -> { // MM:SS.mmm
                    val minutes = parts[0].toLong()
                    val secondsWithMillis = parts[1].split(".")
                    val seconds = secondsWithMillis[0].toLong()
                    val millis = parseMillis(secondsWithMillis.getOrNull(1))
                    
                    minutes * 60_000 + seconds * 1_000 + millis
                }
                else -> {
                    logger.warn("Unknown timestamp format: $timestamp")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse timestamp: $timestamp", e)
            null
        }
    }

    private fun parseMillis(millisStr: String?): Long {
        if (millisStr == null) return 0L
        // Pad or truncate to exactly 3 digits for milliseconds
        val normalized = millisStr.padEnd(3, '0').take(3)
        return normalized.toLong()
    }
}
