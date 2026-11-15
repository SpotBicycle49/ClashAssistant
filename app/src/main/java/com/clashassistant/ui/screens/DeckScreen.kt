package com.clashassistant.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.ui.components.CostBadge
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.theme.ErrorRed
import com.clashassistant.ui.theme.LightViolet
import com.clashassistant.ui.theme.SoftBlue

@Composable
fun DeckScreen(vm: MatchViewModel) {
    val deck by vm.deck.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenTitle("Колода соперника") }
        item {
            SectionCard {
                Text("Известно карт: ${deck.cards.size} из 8   •   Розыгрышей: ${deck.totalPlays}")
                val avg = deck.cards.mapNotNull { CardCatalog.byId(it.cardId)?.cost?.takeIf { c -> c > 0 } }
                if (avg.isNotEmpty()) Text("Средняя стоимость известных карт: %.1f".format(avg.average()))
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
                    Text("Разных карт больше 8 — где-то ошибка разметки. Проверьте события.", color = ErrorRed)
                }
            }
        }
        if (deck.cards.isEmpty()) item { Text("Отметьте розыгрыши в разделе «Анализ».", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(deck.cards, key = { it.cardId }) { c ->
            val card = CardCatalog.byId(c.cardId)
            val (status, color) = when (c.playsUntilInHand) {
                0 -> "В руке" to LightViolet
                1 -> "Следующая" to SoftBlue
                else -> "Вернётся через ${c.playsUntilInHand} розыгр." to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CostBadge(card?.let { CardCatalog.costLabel(it) } ?: "?")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(card?.name ?: c.cardId)
                    Text("Сыграна раз: ${c.timesPlayed}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(status, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
