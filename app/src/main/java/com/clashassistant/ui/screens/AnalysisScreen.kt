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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchUiState
import com.clashassistant.MatchViewModel
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.model.IdMethod
import com.clashassistant.model.PlayEvent
import com.clashassistant.ui.components.CardPickerDialog
import com.clashassistant.ui.components.CostBadge
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.components.formatElixir
import com.clashassistant.ui.components.formatTime
import com.clashassistant.ui.theme.ErrorRed
import com.clashassistant.ui.theme.Warning

@Composable
fun AnalysisScreen(vm: MatchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    var pickerForNew by remember { mutableStateOf(false) }
    var editingEventId by remember { mutableStateOf<Long?>(null) }
    var confirmAuto by remember { mutableStateOf(false) }

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
                        "Выберите запись повтора из журнала боёв. Можно размечать и без видео — " +
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

        // ----- Автораспознавание -----
        if (state.hasVideo) {
            item { AutoRecognitionCard(state, onStart = { if (state.events.isNotEmpty()) confirmAuto = true else vm.startAutoAnalysis() }, onCancel = vm::cancelAnalysis) }
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
                        "Расчёт эликсира соперника: ≈${formatElixir(p.expected)}  (от ${formatElixir(p.low)} до ${formatElixir(p.high)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                actualAt(state)?.let { fact ->
                    Text("Факт из повтора: ${formatElixir(fact)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ----- Ручная разметка -----
        item {
            SectionCard("Розыгрыши соперника") {
                Text(
                    "Если что-то распознано неверно — нажмите карандаш у события. Можно добавить розыгрыш вручную в текущий момент.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pickerForNew = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Добавить розыгрыш в ${formatTime(state.positionMatchSec.coerceAtLeast(0f))}")
                }
            }
        }

        if (state.events.isEmpty()) {
            item { Text("Событий пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(state.events, key = { it.id }) { e ->
            EventRow(
                e = e,
                state = state,
                inconsistent = e.id in inconsistentIds,
                onSeek = { vm.seekTo(e.timeSec) },
                onEdit = { editingEventId = e.id },
                onDelete = { vm.removeEvent(e.id) },
            )
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
    if (confirmAuto) {
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("Заменить разметку?") },
            text = { Text("Автораспознавание заменит текущий список розыгрышей.") },
            confirmButton = { TextButton(onClick = { confirmAuto = false; vm.startAutoAnalysis() }) { Text("Распознать") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AutoRecognitionCard(state: MatchUiState, onStart: () -> Unit, onCancel: () -> Unit) {
    SectionCard("Автораспознавание") {
        val a = state.analysis
        if (a?.running == true) {
            Text("Разбираю видео… ${(a.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { a.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "Не закрывайте приложение. Обычно это занимает 1–3 минуты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) { Text("Отмена") }
        } else {
            Text(
                "Для повторов из журнала боёв: приложение само найдёт все розыгрыши соперника, " +
                    "их стоимость и карты, а также фактический эликсир соперника.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Распознать автоматически") }
            a?.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.stats?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text("Найдено розыгрышей: ${s.plays}", style = MaterialTheme.typography.titleSmall)
            Text(
                "по циклу колоды: ${s.byCycle} • по руке и стоимости: ${s.byHand}" +
                    (if (s.ambiguous > 0) " • неоднозначно: ${s.ambiguous}" else "") +
                    (if (s.unknown > 0) " • не определено: ${s.unknown}" else "") +
                    (if (s.hidden > 0) " • под уведомлением: ${s.hidden}" else "") +
                    (if (s.abilities > 0) " • способности: ${s.abilities}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Карт соперника: ${s.clusters}, подписано автоматически: ${s.autoLabeled}. " +
                    "Кадров: ${s.frames}, время: ${s.seconds.toInt()} с.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unlabeled = state.clusters.count { it.cardId == null }
            if (unlabeled > 0) {
                Text(
                    "Неподписанных карт: $unlabeled — подпишите их в разделе «Колода» (один раз, дальше узнаются сами).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    e: PlayEvent,
    state: MatchUiState,
    inconsistent: Boolean,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val matchTime = e.timeSec - state.matchStartVideoSec
    val name = when {
        e.candidates.size > 1 -> e.candidates.joinToString(" или ") { state.displayName(it.cardId) } + "?"
        e.topCandidate != null -> state.displayName(e.topCandidate!!.cardId)
        else -> "Карта не определена"
    }
    val card = e.topCandidate?.let { CardCatalog.byId(state.resolve(it.cardId)) }
    val costLabel = e.observedCost?.toString() ?: card?.let { CardCatalog.costLabel(it) } ?: "?"
    val methodLabel = when (e.method) {
        IdMethod.CYCLE -> "по циклу"
        IdMethod.HAND -> "по руке"
        IdMethod.ABILITY -> "способность"
        IdMethod.UNKNOWN -> "не определено"
        IdMethod.MANUAL -> "вручную"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = state.thumbnailFor(e)
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.size(width = 36.dp, height = 43.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            CostBadge(costLabel)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (matchTime >= 0) "бой ${formatTime(matchTime)}" else "до начала боя — не учитывается") +
                    " • $costLabel эл. • $methodLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inconsistent) {
                Text("Эликсира не могло хватить — проверьте время или карту", style = MaterialTheme.typography.bodySmall, color = ErrorRed)
            }
        }
        IconButton(onClick = onSeek) { Icon(Icons.Filled.Refresh, "Перейти к моменту") }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Исправить карту") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Удалить") }
    }
}

/** Фактический эликсир соперника из повтора в текущий момент (если есть). */
private fun actualAt(state: MatchUiState): Float? {
    if (state.elixirSeries.isEmpty()) return null
    val t = state.positionVideoSec
    val s = state.elixirSeries.lastOrNull { it.videoTimeSec <= t } ?: return null
    return if (t - s.videoTimeSec <= 1.0f) s.elixir else null
}
