package com.clashassistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.ui.components.CardPickerDialog
import com.clashassistant.ui.components.CostBadge
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.components.formatElixir
import com.clashassistant.ui.components.formatTime
import com.clashassistant.ui.theme.ErrorRed

@Composable
fun AnalysisScreen(vm: MatchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    var pickerForNew by remember { mutableStateOf(false) }
    var editingEventId by remember { mutableStateOf<Long?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openVideo(uri)
    }

    val inconsistentIds = timeline.plays.filter { it.inconsistent }.map { it.event.id }.toSet()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenTitle("Анализ матча") }

        // ----- Видео -----
        item {
            SectionCard("Видеозапись") {
                if (!state.hasVideo) {
                    Text(
                        "Выберите запись матча из памяти телефона. Можно размечать и без видео — " +
                            "тогда шкала будет 5 минут от начала боя.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Выбрать видео") }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 420.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frame = state.frame
                        if (frame != null) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "Кадр видео",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("Кадр загружается…")
                        }
                        if (state.frameLoading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Другое видео") }
                }
            }
        }

        // ----- Шкала времени -----
        item {
            SectionCard("Позиция") {
                Text(
                    "Видео: ${formatTime(state.positionVideoSec)}   •   Бой: " +
                        if (state.positionMatchSec >= 0) formatTime(state.positionMatchSec) else "ещё не начался",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.positionVideoSec,
                    onValueChange = { vm.seekTo(it) },
                    valueRange = 0f..state.timelineLengthSec.coerceAtLeast(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1f, -0.2f, 0.2f, 1f).forEach { d ->
                        OutlinedButton(onClick = { vm.step(d) }, modifier = Modifier.weight(1f)) {
                            Text((if (d > 0) "+" else "") + d.toString().removeSuffix(".0"))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = { vm.markMatchStartHere() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Бой начался здесь (сейчас: ${formatTime(state.matchStartVideoSec)})")
                }
                timeline.estimateAt(state.positionMatchSec.coerceAtLeast(0f))?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Эликсир соперника сейчас: ≈${formatElixir(p.expected)}  (от ${formatElixir(p.low)} до ${formatElixir(p.high)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ----- Разметка -----
        item {
            SectionCard("Карты соперника") {
                Text(
                    "Автоматическое распознавание пока не реализовано — нужен образец записи. " +
                        "Сейчас карты отмечаются вручную: найдите кадр, где соперник сыграл карту, и нажмите кнопку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { pickerForNew = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Соперник сыграл карту в ${formatTime(state.positionMatchSec.coerceAtLeast(0f))}")
                }
            }
        }

        if (state.events.isEmpty()) {
            item { Text("Событий пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(state.events, key = { it.id }) { e ->
            val card = e.topCandidate?.let { CardCatalog.byId(it.cardId) }
            val matchTime = e.timeSec - state.matchStartVideoSec
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CostBadge(card?.let { CardCatalog.costLabel(it) } ?: "?")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(card?.name ?: "Неизвестная карта", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (matchTime >= 0) "бой ${formatTime(matchTime)}" else "до начала боя — не учитывается",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (e.id in inconsistentIds) {
                        Text("Эликсира не могло хватить — проверьте время или карту", style = MaterialTheme.typography.bodySmall, color = ErrorRed)
                    }
                }
                IconButton(onClick = { vm.seekTo(e.timeSec) }) { Icon(Icons.Filled.Refresh, "Перейти к моменту") }
                IconButton(onClick = { editingEventId = e.id }) { Icon(Icons.Filled.Edit, "Исправить карту") }
                IconButton(onClick = { vm.removeEvent(e.id) }) { Icon(Icons.Filled.Delete, "Удалить") }
            }
        }

        // ----- Сохранение -----
        item {
            SectionCard("Сохранение") {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = vm::setTitle,
                    label = { Text("Название матча") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::save, modifier = Modifier.weight(1f)) {
                        Text(if (state.unsavedChanges) "Сохранить" else "Сохранено")
                    }
                    OutlinedButton(onClick = vm::newMatch, modifier = Modifier.weight(1f)) { Text("Новый матч") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickerForNew) {
        CardPickerDialog(
            title = "Какую карту сыграл соперник?",
            onDismiss = { pickerForNew = false },
            onPick = { vm.addEventAtCurrent(it.id); pickerForNew = false },
        )
    }
    editingEventId?.let { id ->
        CardPickerDialog(
            title = "Исправить карту",
            onDismiss = { editingEventId = null },
            onPick = { vm.replaceEventCard(id, it.id); editingEventId = null },
        )
    }
}
