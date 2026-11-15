package com.clashassistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.model.Card

/** Диалог выбора карты: поиск по названию и фильтр по стоимости. */
@Composable
fun CardPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Card) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var costFilter by remember { mutableStateOf<Int?>(null) }
    val filtered = remember(query, costFilter) {
        CardCatalog.all.filter { c ->
            (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true)) &&
                (costFilter == null || c.cost == costFilter)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск карты") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (1..9).toList().forEach { cost ->
                        FilterChip(
                            selected = costFilter == cost,
                            onClick = { costFilter = if (costFilter == cost) null else cost },
                            label = { Text(cost.toString()) },
                        )
                    }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { card ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(card) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CostBadge(CardCatalog.costLabel(card))
                            Text(card.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
