package com.clashassistant

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.data.db.AppDatabase
import com.clashassistant.data.db.MatchEntity
import com.clashassistant.data.prefs.RulesStore
import com.clashassistant.engine.deck.DeckState
import com.clashassistant.engine.deck.DeckTracker
import com.clashassistant.engine.elixir.ElixirEngine
import com.clashassistant.engine.elixir.ElixirRules
import com.clashassistant.engine.elixir.ElixirTimeline
import com.clashassistant.model.CardCandidate
import com.clashassistant.model.PlayEvent
import com.clashassistant.vision.video.VideoFrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Длина шкалы, если матч размечается без видео (5 минут = бой + полный овертайм). */
const val NO_VIDEO_LENGTH_SEC = 300f

data class MatchUiState(
    val matchId: Long? = null,
    val title: String = "",
    val videoUri: Uri? = null,
    val videoDurationSec: Float = 0f,
    /** Момент видео, когда начался бой (таймер 3:00). */
    val matchStartVideoSec: Float = 0f,
    /** Текущая позиция на шкале (время видео). */
    val positionVideoSec: Float = 0f,
    /** События; timeSec = время ВИДЕО. */
    val events: List<PlayEvent> = emptyList(),
    val frame: Bitmap? = null,
    val frameLoading: Boolean = false,
    val unsavedChanges: Boolean = false,
    val message: String? = null,
) {
    val hasVideo: Boolean get() = videoUri != null
    val timelineLengthSec: Float get() = if (hasVideo) videoDurationSec else NO_VIDEO_LENGTH_SEC
    val positionMatchSec: Float get() = positionVideoSec - matchStartVideoSec
}

class MatchViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).matchDao()
    private val rulesStore = RulesStore(app)
    private val frameSource = VideoFrameSource(app)
    private var frameJob: Job? = null
    private var nextLocalId = 1L

    private val _state = MutableStateFlow(MatchUiState())
    val state: StateFlow<MatchUiState> = _state.asStateFlow()

    private val _rules = MutableStateFlow(rulesStore.load())
    val rules: StateFlow<ElixirRules> = _rules.asStateFlow()

    /** События во времени МАТЧА (только после начала боя). */
    private val matchEvents = _state
        .map { s -> Pair(s.events, s.matchStartVideoSec) to (s.timelineLengthSec - s.matchStartVideoSec) }
        .distinctUntilChanged()

    val timeline: StateFlow<ElixirTimeline> = combine(matchEvents, _rules) { (pair, length), rules ->
        val (events, start) = pair
        val shifted = events.map { it.copy(timeSec = it.timeSec - start) }.filter { it.timeSec >= 0f }
        val end = max(max(length, 1f), (shifted.maxOfOrNull { it.timeSec } ?: 0f) + 1f)
        ElixirEngine(rules, CardCatalog::byId).compute(shifted, end)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ElixirTimeline.EMPTY)

    val deck: StateFlow<DeckState> = _state
        .map { s -> s.events.filter { it.timeSec >= s.matchStartVideoSec } }
        .distinctUntilChanged()
        .map { events -> DeckTracker.analyze(events.sortedBy { it.timeSec }.mapNotNull { it.topCandidate?.cardId }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeckState.EMPTY)

    val history: StateFlow<List<MatchEntity>> = dao.observeMatches()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Видео ----------

    fun openVideo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                val duration = withContext(Dispatchers.IO) { frameSource.open(uri) }
                _state.update {
                    it.copy(
                        videoUri = uri, videoDurationSec = duration, positionVideoSec = 0f,
                        matchStartVideoSec = 0f, frame = null, unsavedChanges = true,
                    )
                }
                loadFrame(0f)
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Не удалось открыть видео") }
            }
        }
    }

    fun seekTo(videoSec: Float) {
        val s = _state.value
        val clamped = videoSec.coerceIn(0f, s.timelineLengthSec)
        _state.update { it.copy(positionVideoSec = clamped) }
        loadFrame(clamped)
    }

    fun step(deltaSec: Float) = seekTo(_state.value.positionVideoSec + deltaSec)

    private fun loadFrame(sec: Float) {
        if (!_state.value.hasVideo) return
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            delay(80) // не декодируем каждый шаг ползунка
            _state.update { it.copy(frameLoading = true) }
            val bmp = withContext(Dispatchers.IO) { frameSource.frameAt(sec) }
            _state.update { it.copy(frame = bmp ?: it.frame, frameLoading = false) }
        }
    }

    fun markMatchStartHere() {
        _state.update { it.copy(matchStartVideoSec = it.positionVideoSec, unsavedChanges = true) }
    }

    // ---------- События ----------

    fun addEventAtCurrent(cardId: String) {
        val id = nextLocalId++
        _state.update {
            val e = PlayEvent(id, it.positionVideoSec, listOf(CardCandidate(cardId, 1f)), confirmed = true)
            it.copy(events = (it.events + e).sortedBy { ev -> ev.timeSec }, unsavedChanges = true)
        }
    }

    fun replaceEventCard(eventId: Long, cardId: String) {
        _state.update {
            it.copy(
                events = it.events.map { e ->
                    if (e.id == eventId) e.copy(candidates = listOf(CardCandidate(cardId, 1f)), confirmed = true) else e
                },
                unsavedChanges = true,
            )
        }
    }

    fun moveEventToCurrent(eventId: Long) {
        _state.update {
            it.copy(
                events = it.events.map { e -> if (e.id == eventId) e.copy(timeSec = it.positionVideoSec) else e }
                    .sortedBy { e -> e.timeSec },
                unsavedChanges = true,
            )
        }
    }

    fun removeEvent(eventId: Long) {
        _state.update { it.copy(events = it.events.filterNot { e -> e.id == eventId }, unsavedChanges = true) }
    }

    fun setTitle(title: String) = _state.update { it.copy(title = title, unsavedChanges = true) }

    // ---------- Матчи ----------

    fun newMatch() {
        frameJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { frameSource.close() }
        _state.value = MatchUiState()
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            val title = s.title.ifBlank { "Матч от " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date()) }
            val entity = MatchEntity(
                id = s.matchId ?: 0L,
                title = title,
                createdAt = System.currentTimeMillis(),
                videoUri = s.videoUri?.toString(),
                videoDurationSec = s.videoDurationSec,
                matchStartVideoSec = s.matchStartVideoSec,
                eventCount = s.events.size,
            )
            val id = withContext(Dispatchers.IO) { dao.saveMatch(entity, s.events) }
            _state.update { it.copy(matchId = id, title = title, unsavedChanges = false, message = "Матч сохранён") }
        }
    }

    fun load(matchId: Long) {
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { dao.getMatch(matchId) } ?: return@launch
            val events = withContext(Dispatchers.IO) { dao.getEvents(matchId) }.map { it.toModel() }
            nextLocalId = (events.maxOfOrNull { it.id } ?: 0L) + 1
            var uri: Uri? = m.videoUri?.let(Uri::parse)
            var duration = m.videoDurationSec
            var msg: String? = null
            if (uri != null) {
                try {
                    duration = withContext(Dispatchers.IO) { frameSource.open(uri!!) }
                } catch (e: Exception) {
                    uri = null
                    msg = "Видео этого матча недоступно (файл удалён или перемещён). Разметка загружена."
                }
            } else {
                withContext(Dispatchers.IO) { frameSource.close() }
            }
            _state.value = MatchUiState(
                matchId = m.id, title = m.title, videoUri = uri,
                videoDurationSec = if (uri != null) duration else 0f,
                matchStartVideoSec = m.matchStartVideoSec,
                positionVideoSec = m.matchStartVideoSec, events = events, message = msg,
            )
            loadFrame(m.matchStartVideoSec)
        }
    }

    fun delete(matchId: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteMatch(matchId) }
        if (_state.value.matchId == matchId) _state.update { it.copy(matchId = null, unsavedChanges = true) }
    }

    fun updateRules(rules: ElixirRules) {
        rulesStore.save(rules)
        _rules.value = rules
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        frameSource.close()
    }
}
