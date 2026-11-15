package com.clashassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.Tab
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.theme.LightViolet
import com.clashassistant.ui.theme.Warning

@Composable
fun HomeScreen(vm: MatchViewModel, onNavigate: (Tab) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Clash Assistant", style = MaterialTheme.typography.headlineMedium, color = LightViolet)
        Text(
            "Разбор записей матчей Clash Royale после игры: карты соперника, цикл колоды и расчётный эликсир.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("Начать") {
            Button(onClick = { vm.newMatch(); onNavigate(Tab.ANALYSIS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Новый анализ")
            }
            if (state.events.isNotEmpty() || state.hasVideo) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onNavigate(Tab.ANALYSIS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Продолжить текущий (${state.events.size} событий)")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Сохранено матчей: ${history.size}", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Что уже работает") {
            Status(true, "Импорт видео и покадровый просмотр")
            Status(true, "Ручная отметка и исправление карт соперника")
            Status(true, "Расчёт эликсира с диапазоном неопределённости")
            Status(true, "Восстановление колоды и цикла карт")
            Status(true, "История матчей (хранится на телефоне)")
            Status(false, "Автоматическое распознавание карт — в разработке")
        }

        SectionCard("Правила использования") {
            Text(
                "Приложение работает только с готовыми видеофайлами после матча. Оно не подключается к игре, " +
                    "не читает её память и трафик и не показывает подсказки во время боя — такие функции " +
                    "нарушили бы правила Supercell.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(done: Boolean, text: String) {
    Text(
        (if (done) "✓  " else "…  ") + text,
        color = if (done) Color.Unspecified else Warning,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
