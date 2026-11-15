package com.clashassistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashassistant.ui.theme.Violet
import com.clashassistant.ui.theme.LightViolet
import java.util.Locale

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            content()
        }
    }
}

/** Кружок со стоимостью в эликсире. */
@Composable
fun CostBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .background(Brush.linearGradient(listOf(LightViolet, Violet)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** 75.3 → "1:15.3" */
fun formatTime(sec: Float): String {
    val sign = if (sec < 0) "-" else ""
    val a = kotlin.math.abs(sec)
    val m = (a / 60).toInt()
    val s = a - m * 60
    return sign + String.format(Locale.US, "%d:%04.1f", m, s)
}

fun formatElixir(v: Float): String = String.format(Locale.US, "%.1f", v)
