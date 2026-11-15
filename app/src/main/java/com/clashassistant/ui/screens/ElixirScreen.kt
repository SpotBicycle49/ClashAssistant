package com.clashassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.ui.components.CostBadge
import com.clashassistant.ui.components.ElixirChart
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.components.formatElixir
import com.clashassistant.ui.components.formatTime
import com.clashassistant.ui.theme.ErrorRed

@Composable
fun ElixirScreen(vm: MatchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val now = state.positionMatchSec

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenTitle("Эликсир соперника") }
        item {
            SectionCard("Оценка в текущий момент (${formatTime(now.coerceAtLeast(0f))})") {
                val p = timeline.estimateAt(now.coerceAtLeast(0f))
                if (p == null) {
                    Text("Нет данных")
                } else {
                    Text("≈ ${formatElixir(p.expected)}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("Возможный диапазон: ${formatElixir(p.low)} – ${formatElixir(p.high)}")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Это расчётная оценка, а не точное значение. Диапазон сужается, когда соперник разыгрывает дорогие карты.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard("График") {
                ElixirChart(timeline, rules, currentMatchSec = now)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Полоса — возможный диапазон, линия — ожидаемое значение. Синий пунктир — начало двойного эликсира, " +
                        "оранжевый — тройного. Красные точки — розыгрыши, на которые по модели не хватало эликсира.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (timeline.skippedEvents > 0) {
                    Text("Пропущено событий с неизвестной картой: ${timeline.skippedEvents}", color = ErrorRed)
                }
            }
        }
        item { Text("Розыгрыши", style = MaterialTheme.typography.titleMedium) }
        if (timeline.plays.isEmpty()) item { Text("Пока нет розыгрышей после начала боя.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(timeline.plays, key = { it.event.id }) { play ->
            val card = play.event.topCandidate?.let { CardCatalog.byId(it.cardId) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CostBadge(if (play.minCost == play.maxCost) play.minCost.toString() else "${play.minCost}-${play.maxCost}")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${formatTime(play.event.timeSec)}  ${card?.name ?: "?"}")
                    Text(
                        "было ≈${formatElixir(play.before.expected)} → стало ≈${formatElixir(play.after.expected)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (play.inconsistent) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
