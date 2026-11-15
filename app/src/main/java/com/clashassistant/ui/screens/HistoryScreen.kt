package com.clashassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.data.db.MatchEntity
import com.clashassistant.ui.components.ScreenTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MatchViewModel, onOpen: (Long) -> Unit) {
    val matches by vm.history.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<MatchEntity?>(null) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)); ScreenTitle("История матчей") }
        if (matches.isEmpty()) item { Text("Сохранённых матчей пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(matches, key = { it.id }) { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onOpen(m.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${fmt.format(Date(m.createdAt))}  •  событий: ${m.eventCount}" + if (m.videoUri == null) "  •  без видео" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { toDelete = m }) { Icon(Icons.Filled.Delete, "Удалить") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить матч?") },
            text = { Text("«${m.title}» и вся его разметка будут удалены. Видео на телефоне останется.") },
            confirmButton = { TextButton(onClick = { vm.delete(m.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
