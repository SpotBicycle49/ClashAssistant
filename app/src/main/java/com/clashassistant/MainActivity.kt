package com.clashassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clashassistant.ui.screens.AnalysisScreen
import com.clashassistant.ui.screens.DeckScreen
import com.clashassistant.ui.screens.ElixirScreen
import com.clashassistant.ui.screens.HistoryScreen
import com.clashassistant.ui.screens.HomeScreen
import com.clashassistant.ui.screens.SettingsScreen
import com.clashassistant.ui.theme.ClashAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClashAssistantTheme { ClashAssistantApp() }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    ANALYSIS("Анализ", Icons.Filled.PlayArrow),
    DECK("Колода", Icons.AutoMirrored.Filled.List),
    ELIXIR("Эликсир", Icons.Filled.Star),
    HISTORY("История", Icons.Filled.DateRange),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

@Composable
fun ClashAssistantApp(vm: MatchViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(vm, onNavigate = { tab = it })
                Tab.ANALYSIS -> AnalysisScreen(vm)
                Tab.DECK -> DeckScreen(vm)
                Tab.ELIXIR -> ElixirScreen(vm)
                Tab.HISTORY -> HistoryScreen(vm, onOpen = { id -> vm.load(id); tab = Tab.ANALYSIS })
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
