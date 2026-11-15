package com.clashassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashassistant.MatchViewModel
import com.clashassistant.engine.elixir.ElixirRules
import com.clashassistant.ui.components.ScreenTitle
import com.clashassistant.ui.components.SectionCard
import com.clashassistant.ui.theme.ErrorRed

@Composable
fun SettingsScreen(vm: MatchViewModel) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val libraryCount by vm.libraryCount.collectAsStateWithLifecycle()
    var start by remember(rules) { mutableStateOf(rules.startElixir.toString()) }
    var max by remember(rules) { mutableStateOf(rules.maxElixir.toString()) }
    var spe by remember(rules) { mutableStateOf(rules.secondsPerElixir.toString()) }
    var dbl by remember(rules) { mutableStateOf(rules.doubleStartSec.toString()) }
    var tpl by remember(rules) { mutableStateOf(rules.tripleStartSec.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Настройки")
        SectionCard("Правила эликсира") {
            Text(
                "Время указывается в секундах от начала боя. Если Supercell изменит правила, поправьте значения здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField("Эликсир на старте", start) { start = it }
            NumberField("Максимум эликсира", max) { max = it }
            NumberField("Секунд на 1 эликсир (обычное время)", spe) { spe = it }
            NumberField("Начало двойного эликсира, с", dbl) { dbl = it }
            NumberField("Начало тройного эликсира, с", tpl) { tpl = it }
            error?.let { Text(it, color = ErrorRed) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    val parsed = listOf(start, max, spe, dbl, tpl).map { it.replace(',', '.').toFloatOrNull() }
                    if (parsed.any { it == null || it < 0f } || parsed[2] == 0f) {
                        error = "Проверьте значения: нужны положительные числа"
                    } else {
                        error = null
                        vm.updateRules(ElixirRules(parsed[0]!!, parsed[1]!!, parsed[2]!!, parsed[3]!!, parsed[4]!!))
                    }
                }) { Text("Сохранить") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { error = null; vm.updateRules(ElixirRules()) }) {
                    Text("По умолчанию")
                }
            }
        }
        SectionCard("Библиотека образцов карт") {
            Text("Сохранено образцов: $libraryCount. Они появляются, когда вы подписываете карты соперника, и позволяют узнавать карты автоматически.")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::clearLibrary, enabled = libraryCount > 0) { Text("Очистить библиотеку") }
        }
        SectionCard("О приложении") {
            Text("Версия 0.3.0. Все данные хранятся только на этом телефоне, интернет не используется.")
            Spacer(Modifier.height(6.dp))
            Text(
                "Не реализовано пока: распознавание записей своих боёв (где не видна рука соперника), учёт Сборщика эликсира, режимы 2×2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}
