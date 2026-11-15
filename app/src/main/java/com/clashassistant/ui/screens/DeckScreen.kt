package com.clashassistant.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.model.CardIds
import com.clashassistant.ui.components.CardPickerDialog
import com.clashassistant.ui.components.CostBadge
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.theme.ErrorRed
import com.clashassistant.ui.theme.LightViolet
import com.clashassistant.ui.theme.SoftBlue
import com.clashassistant.ui.theme.Warning

@Composable
fun DeckScreen(vm: MatchViewModel) {
    val deck by vm.deck.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var labeling by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenTitle("Колода соперника") }

        // ----- Карты из автораспознавания -----
        if (state.clusters.isNotEmpty()) {
            item {
                SectionCard("Карты соперника из повтора") {
                    Text(
                        "Подпишите каждую карту один раз — в следующих матчах она будет узнаваться сама. " +
                            "Если одна карта встречается дважды (обычная и эволюция), подпишите обе одинаково.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.clusters, key = { "c" + it.id }) { c ->
                val card = c.cardId?.let { CardCatalog.byId(it) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumb = state.thumbnails[c.id]
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = card?.name ?: "Карта №${c.id + 1}",
                            modifier = Modifier.size(width = 52.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    } else {
                        CostBadge(c.cost?.toString() ?: "?")
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card?.name ?: "Карта №${c.id + 1}", style = MaterialTheme.typography.titleSmall)
                        val costText = when {
                            c.variableCost -> "стоимость меняется (похоже на Зеркало)"
                            c.cost != null -> "стоимость ${c.cost}"
                            else -> "стоимость пока неизвестна"
                        }
                        Text(
                            "$costText • сыграна: ${c.timesPlayed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (card == null) Text("Не подписана", style = MaterialTheme.typography.bodySmall, color = Warning)
                    }
                    if (card == null) {
                        OutlinedButton(onClick = { labeling = c.id }) { Text("Подписать") }
                    } else {
                        TextButton(onClick = { labeling = c.id }) { Text("Изменить") }
                    }
                }
            }
        }

        // ----- Цикл -----
        item {
            SectionCard("Цикл карт") {
                Text("Известно карт: ${deck.cards.size} из 8   •   Розыгрышей: ${deck.totalPlays}")
                val costs = deck.cards.mapNotNull { costOf(it.cardId, state.clusters.associate { c -> c.id to c.cost }) }
                if (costs.isNotEmpty()) Text("Средняя стоимость известных карт: %.1f".format(costs.average()))
                if (deck.cycleComplete) {
                    Text("Колода восстановлена полностью — цикл известен точно.", color = LightViolet)
                } else if (deck.unknownCount > 0 && deck.totalPlays > 0) {
                    Text(
                        "Неизвестно карт: ${deck.unknownCount}. Пока колода не известна полностью, " +
                            "статус «в руке» верен только для уже сыгранных карт.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deck.tooManyCards) {
                    Text(
                        "Разных карт больше 8. Если это эволюция одной карты — подпишите обе одинаково; иначе проверьте разметку.",
                        color = ErrorRed,
                    )
                }
            }
        }
        if (deck.cards.isEmpty()) item {
            Text("Распознайте повтор или отметьте розыгрыши в разделе «Анализ».", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(deck.cards, key = { it.cardId }) { c ->
            val (status, color) = when (c.playsUntilInHand) {
                0 -> "В руке" to LightViolet
                1 -> "Следующая" to SoftBlue
                else -> "Вернётся через ${c.playsUntilInHand} розыгр." to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val k = CardIds.clusterOf(c.cardId)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumb = k?.let { state.thumbnails[it] }
                    ?: state.clusters.firstOrNull { it.cardId == c.cardId }?.let { state.thumbnails[it.id] }
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(width = 36.dp, height = 43.dp).clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    CostBadge(CardCatalog.byId(c.cardId)?.let { CardCatalog.costLabel(it) } ?: "?")
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.displayName(c.cardId))
                    Text("Сыграна раз: ${c.timesPlayed}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(status, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    labeling?.let { id ->
        CardPickerDialog(
            title = "Что это за карта?",
            onDismiss = { labeling = null },
            onPick = { vm.labelCluster(id, it.id); labeling = null },
        )
    }
}

private fun costOf(cardId: String, clusterCosts: Map<Int, Int?>): Int? {
    CardIds.clusterOf(cardId)?.let { return clusterCosts[it] }
    return CardCatalog.byId(cardId)?.cost?.takeIf { it > 0 }
}
