package com.clashassistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.clashassistant.engine.elixir.ElixirRules
import com.clashassistant.engine.elixir.ElixirTimeline
import com.clashassistant.ui.theme.ErrorRed
import com.clashassistant.ui.theme.SoftBlue
import com.clashassistant.ui.theme.Warning

/**
 * График предполагаемого эликсира: полоса — интервал [мин; макс], линия — ожидаемое значение,
 * точки — розыгрыши (красные — противоречие модели), вертикальная линия — текущий момент.
 */
@Composable
fun ElixirChart(
    timeline: ElixirTimeline,
    rules: ElixirRules,
    currentMatchSec: Float?,
    modifier: Modifier = Modifier,
) {
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val playColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val pts = timeline.points
        if (pts.isEmpty()) return@Canvas
        val end = timeline.endTimeSec.coerceAtLeast(1f)
        val maxY = rules.maxElixir.coerceAtLeast(1f)
        fun x(t: Float) = t / end * size.width
        fun y(v: Float) = size.height - v / maxY * size.height

        // Сетка: 0, 5, 10
        listOf(0f, maxY / 2f, maxY).forEach { v ->
            drawLine(gridColor, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
        }
        // Границы двойного и тройного эликсира
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        listOf(rules.doubleStartSec to SoftBlue, rules.tripleStartSec to Warning).forEach { (t, c) ->
            if (t < end) drawLine(c, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 2f, pathEffect = dash)
        }

        // Полоса неопределённости
        val band = Path().apply {
            moveTo(x(pts[0].timeSec), y(pts[0].high))
            pts.forEach { lineTo(x(it.timeSec), y(it.high)) }
            for (i in pts.indices.reversed()) lineTo(x(pts[i].timeSec), y(pts[i].low))
            close()
        }
        drawPath(band, bandColor)

        // Ожидаемое значение
        val line = Path().apply {
            moveTo(x(pts[0].timeSec), y(pts[0].expected))
            pts.forEach { lineTo(x(it.timeSec), y(it.expected)) }
        }
        drawPath(line, lineColor, style = Stroke(width = 4f))

        // Розыгрыши
        timeline.plays.forEach { p ->
            drawCircle(
                color = if (p.inconsistent) ErrorRed else playColor,
                radius = 7f,
                center = Offset(x(p.after.timeSec), y(p.after.expected)),
            )
        }

        currentMatchSec?.let { t ->
            if (t in 0f..end) drawLine(lineColor, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 2f)
        }
    }
}
