package com.langkraft.backend

import com.langkraft.domain.model.SubtitleLine
import java.util.UUID

/**
 * A simple SRT parser to convert raw subtitle files into Domain models.
 */
object SrtParser {
    fun parse(contentId: String, srtContent: String): List<SubtitleLine> {
        val lines = srtContent.lines()
        val cues = mutableListOf<SubtitleLine>()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }
            
            // Skip index line
            i++ 
            
            // Parse timestamps (00:00:20,000 --> 00:00:24,400)
            val timeRange = lines[i].split(" --> ")
            val startMs = parseTimestamp(timeRange[0])
            val endMs = parseTimestamp(timeRange[1])
            i++
            
            // Parse text (can be multiple lines until empty line)
            val textBuilder = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                textBuilder.append(lines[i].trim())
                i++
            }
            
            cues.add(SubtitleLine(
                id = UUID.randomUUID().toString(),
                contentId = contentId,
                startMs = startMs,
                endMs = endMs,
                textDe = textBuilder.toString(),
                textEn = null
            ))
        }
        return cues
    }

    private fun parseTimestamp(timestamp: String): Long {
        val parts = timestamp.replace(",", ".").split(":")
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val secondsWithMs = parts[2].toDouble()
        return (hours * 3600000 + minutes * 60000 + (secondsWithMs * 1000).toLong())
    }
}
