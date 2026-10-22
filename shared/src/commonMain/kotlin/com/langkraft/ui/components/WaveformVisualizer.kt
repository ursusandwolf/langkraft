package com.langkraft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WaveformVisualizer(
    amplitudes: List<Float>,
    progress: Float, // 0.0 to 1.0
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colors.primary,
    inactiveColor: Color = Color.LightGray
) {
    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        val width = size.width
        val height = size.height
        val barCount = amplitudes.size
        
        if (barCount == 0) return@Canvas
        
        val barWidth = width / barCount
        val gap = 2.dp.toPx()
        
        amplitudes.forEachIndexed { index, amplitude ->
            val x = index * barWidth
            val barHeight = amplitude * height
            val color = if (index.toFloat() / barCount <= progress) activeColor else inactiveColor
            
            drawRect(
                color = color,
                topLeft = Offset(x + gap / 2, (height - barHeight) / 2),
                size = Size(barWidth - gap, barHeight)
            )
        }
    }
}
