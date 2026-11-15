package com.clashassistant

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.data.db.AppDatabase
import com.clashassistant.data.db.CardTemplateEntity
import com.clashassistant.data.db.MatchEntity
import com.clashassistant.data.db.Signatures
import com.clashassistant.data.prefs.RulesStore
import com.clashassistant.engine.deck.DeckState
import com.clashassistant.engine.deck.DeckTracker
import com.clashassistant.engine.elixir.ElixirEngine
import com.clashassistant.engine.elixir.ElixirRules
import com.clashassistant.engine.elixir.ElixirTimeline
import com.clashassistant.model.CardCandidate
import com.clashassistant.model.CardIds
import com.clashassistant.model.IdMethod
import com.clashassistant.model.OpponentCluster
import com.clashassistant.model.PlayEvent
import com.clashassistant.vision.replay.ElixirSample
import com.clashassistant.vision.replay.NotAReplayException
import com.clashassistant.vision.replay.ReplayAnalysis
import com.clashassistant.vision.replay.ReplayAnalyzer
import com.clashassistant.vision.replay.ReplayDetector
import com.clashassistant.vision.video.VideoFrameSource
import kotlinx.coroutines.CancellationException
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Длина шкалы, если матч размечается без видео (5 минут = бой + полный овертайм). */
const val NO_VIDEO_LENGTH_SEC = 300f

/** Порог сходства для автоматической подписи карты по библиотеке образцов. */
private const val LIBRARY_MATCH = 0.90f

data class AnalysisProgress(val running: Boolean, val progress: Float, val error: String? = null)

data class RecognitionStats(
    val plays: Int,
    val byCycle: Int,
    val byHand: Int,
    val ambiguous: Int,
    val unknown: Int,
    /** Розыгрыши, найденные по смене «След.» без видимого падения эликсира (например, под уведомлением). */
    val hidden: Int,
    val abilities: Int,
    val clusters: Int,
    val autoLabeled: Int,
    val frames: Int,
    val seconds: Float,
)

/** Сравнение расчёта эликсира с фактом из повтора. */
data class ElixirAccuracy(val meanAbsError: Float, val withinOne: Float, val samples: Int)

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
    /** Карты соперника, найденные автораспознаванием. */
    val clusters: List<OpponentCluster> = emptyList(),
    val thumbnails: Map<Int, Bitmap> = emptyMap(),
    /** Фактический эликсир соперника из повтора (время видео). */
    val elixirSeries: List<ElixirSample> = emptyList(),
    /** Эликсир соперника на 3:00 по данным повтора. */
    val startElixir: Float? = null,
    val analysis: AnalysisProgress? = null,
    val stats: RecognitionStats? = null,
) {
    val hasVideo: Boolean get() = videoUri != null
    val timelineLengthSec: Float get() = if (hasVideo) videoDurationSec else NO_VIDEO_LENGTH_SEC
    val positionMatchSec: Float get() = positionVideoSec - matchStartVideoSec

    /** "cluster:N" → подпись карты (если есть), иначе как есть. */
    fun resolve(cardId: String): String {
        val k = CardIds.clusterOf(cardId) ?: return cardId
        return clusters.firstOrNull { it.id == k }?.cardId ?: cardId
    }

    fun displayName(cardId: String): String {
        if (cardId == CardIds.ABILITY) return "Способность / прочий расход"
        val resolved = resolve(cardId)
        CardCatalog.byId(resolved)?.let { return it.name }
        val k = CardIds.clusterOf(resolved)
        return if (k != null) "Карта №${k + 1} (не подписана)" else resolved
    }

    fun thumbnailFor(event: PlayEvent): Bitmap? {
        val k = event.clusterId ?: event.topCandidate?.cardId?.let { CardIds.clusterOf(it) } ?: return null
        return thumbnails[k]
    }
}

class MatchViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).matchDao()
    private val rulesStore = RulesStore(app)
    private val frameSource = VideoFrameSource(app)
    private var frameJob: Job? = null
    private var analysisJob: Job? = null
    private var nextLocalId = 1L

    private val _state = MutableStateFlow(MatchUiState())
    val state: StateFlow<MatchUiState> = _state.asStateFlow()

    private val _rules = MutableStateFlow(rulesStore.load())
    val rules: StateFlow<ElixirRules> = _rules.asStateFlow()

    private data class EngineInput(
        val events: List<PlayEvent>,
        val start: Float,
        val length: Float,
        val startElixir: Float?,
        /** Подписи карт соперника: кластер → карта (нужны, если стоимость розыгрыша не была видна). */
        val labels: Map<Int, String>,
    )

    val timeline: StateFlow<ElixirTimeline> = combine(
        _state.map { s ->
            EngineInput(
                s.events, s.matchStartVideoSec, s.timelineLengthSec - s.matchStartVideoSec, s.startElixir,
                s.clusters.mapNotNull { c -> c.cardId?.let { c.id to it } }.toMap(),
            )
        }.distinctUntilChanged(),
        _rules,
    ) { input, rules ->
        val shifted = input.events.map { e ->
            e.copy(
                timeSec = e.timeSec - input.start,
                candidates = e.candidates.map { c ->
                    CardIds.clusterOf(c.cardId)?.let { input.labels[it] }?.let { id -> c.copy(cardId = id) } ?: c
                },
            )
        }.filter { it.timeSec >= 0f }
        val end = max(max(input.length, 1f), (shifted.maxOfOrNull { it.timeSec } ?: 0f) + 1f)
        val effective = input.startElixir?.let { rules.copy(startElixir = it) } ?: rules
        ElixirEngine(effective, CardCatalog::byId).compute(shifted, end)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ElixirTimeline.EMPTY)

    val deck: StateFlow<DeckState> = _state
        .map { s ->
            s.events
                .filter { it.timeSec >= s.matchStartVideoSec && !it.isAbility }
                .sortedBy { it.timeSec }
                .mapNotNull { e -> e.topCandidate?.cardId?.let { s.resolve(it) } }
        }
        .distinctUntilChanged()
        .map { ids -> DeckTracker.analyze(ids) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeckState.EMPTY)

    /** Насколько расчёт эликсира совпадает с фактическим эликсиром из повтора. */
    val accuracy: StateFlow<ElixirAccuracy?> = combine(timeline, _state) { tl, s ->
        if (s.elixirSeries.isEmpty() || tl.points.isEmpty()) return@combine null
        var sum = 0f
        var within = 0
        var n = 0
        for (sample in s.elixirSeries) {
            val mt = sample.videoTimeSec - s.matchStartVideoSec
            if (mt < 0f || mt > tl.endTimeSec) continue
            val est = tl.estimateAt(mt) ?: continue
            val err = abs(est.expected - sample.elixir)
            sum += err
            if (err <= 1f) within++
            n++
        }
        if (n == 0) null else ElixirAccuracy(sum / n, within.toFloat() / n, n)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val history: StateFlow<List<MatchEntity>> = dao.observeMatches()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val libraryCount: StateFlow<Int> = dao.observeTemplateCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ---------- Видео ----------

    fun openVideo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                val duration = withContext(Dispatchers.IO) { frameSource.open(uri) }
                analysisJob?.cancel()
                _state.update {
                    it.copy(
                        videoUri = uri, videoDurationSec = duration, positionVideoSec = 0f,
                        matchStartVideoSec = 0f, frame = null, unsavedChanges = true,
                        // Данные автораспознавания относились к прошлому видео
                        events = it.events.filter { e -> e.method == IdMethod.MANUAL },
                        clusters = emptyList(), thumbnails = emptyMap(), elixirSeries = emptyList(),
                        startElixir = null, stats = null, analysis = null,
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

    // ---------- Автораспознавание ----------

    fun startAutoAnalysis() {
        val uri = _state.value.videoUri ?: return
        analysisJob?.cancel()
        _state.update { it.copy(analysis = AnalysisProgress(true, 0f)) }
        analysisJob = viewModelScope.launch {
            try {
                val result = ReplayAnalyzer(getApplication()).analyze(uri) { p ->
                    _state.update { s -> if (s.analysis?.running == true) s.copy(analysis = AnalysisProgress(true, p)) else s }
                }
                applyAnalysis(result)
            } catch (e: CancellationException) {
                _state.update { it.copy(analysis = null) }
                throw e
            } catch (e: NotAReplayException) {
                _state.update { it.copy(analysis = AnalysisProgress(false, 0f, e.message)) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(analysis = AnalysisProgress(false, 0f, "Не хватило памяти для разбора видео.")) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(analysis = AnalysisProgress(false, 0f, "Ошибка распознавания: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        _state.update { it.copy(analysis = null) }
    }

    private suspend fun applyAnalysis(result: ReplayAnalysis) {
        val det = result.detection
        val library = withContext(Dispatchers.IO) { dao.getTemplates() }
            .map { it.cardId to Signatures.decode(it.signature) }

        var autoLabeled = 0
        val labels = HashMap<Int, String>()
        for (c in det.clusters) {
            var bestId: String? = null
            var best = -2f
            for ((cardId, sig) in library) {
                if (sig.size != c.prototype.size) continue
                val sim = ReplayDetector.dot(sig, c.prototype)
                if (sim > best) { best = sim; bestId = cardId }
            }
            if (bestId != null && best >= LIBRARY_MATCH) { labels[c.id] = bestId; autoLabeled++ }
        }

        val played = HashMap<Int, Int>()
        det.plays.forEach { p -> p.clusterCandidates.singleOrNull()?.let { played[it] = (played[it] ?: 0) + 1 } }
        val clusters = det.clusters.map { c ->
            OpponentCluster(
                id = c.id,
                signature = c.prototype,
                thumbnail = result.thumbnails[c.id],
                cardId = labels[c.id],
                cost = det.clusterCosts[c.id],
                variableCost = c.id in det.variableCostClusters,
                timesPlayed = played[c.id] ?: 0,
            )
        }
        val thumbs = decodeThumbs(clusters)

        val events = det.plays.map { p ->
            val cands = when {
                p.method == IdMethod.ABILITY -> listOf(CardCandidate(CardIds.ABILITY, 1f))
                else -> p.clusterCandidates.map { CardCandidate(CardIds.cluster(it), 1f / p.clusterCandidates.size) }
            }
            PlayEvent(
                id = nextLocalId++,
                timeSec = p.videoTimeSec,
                candidates = cands,
                confirmed = false,
                observedCost = p.cost,
                clusterId = p.clusterCandidates.singleOrNull(),
                method = p.method,
            )
        }
        val stats = RecognitionStats(
            plays = det.plays.count { it.method != IdMethod.ABILITY },
            byCycle = det.plays.count { it.method == IdMethod.CYCLE },
            byHand = det.plays.count { it.method == IdMethod.HAND && it.clusterCandidates.size == 1 },
            ambiguous = det.plays.count { it.clusterCandidates.size > 1 },
            unknown = det.plays.count { it.method == IdMethod.UNKNOWN },
            hidden = det.plays.count { it.virtual },
            abilities = det.plays.count { it.method == IdMethod.ABILITY },
            clusters = clusters.size,
            autoLabeled = autoLabeled,
            frames = result.framesProcessed,
            seconds = result.seconds,
        )
        _state.update {
            it.copy(
                events = events,
                clusters = clusters,
                thumbnails = thumbs,
                elixirSeries = det.elixirSeries,
                startElixir = det.startElixir,
                matchStartVideoSec = det.matchStartVideoSec,
                positionVideoSec = det.matchStartVideoSec,
                analysis = null,
                stats = stats,
                unsavedChanges = true,
                message = "Найдено розыгрышей: ${stats.plays}",
            )
        }
        loadFrame(det.matchStartVideoSec)
    }

    private fun decodeThumbs(clusters: List<OpponentCluster>): Map<Int, Bitmap> =
        clusters.mapNotNull { c ->
            c.thumbnail?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { c.id to it } }
        }.toMap()

    /** Подписать карту соперника: применяется ко всем её розыгрышам и сохраняется в библиотеку. */
    fun labelCluster(clusterId: Int, cardId: String) {
        val cluster = _state.value.clusters.firstOrNull { it.id == clusterId } ?: return
        _state.update { s ->
            s.copy(
                clusters = s.clusters.map { if (it.id == clusterId) it.copy(cardId = cardId) else it },
                unsavedChanges = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertTemplate(CardTemplateEntity(cardId = cardId, signature = Signatures.encode(cluster.signature), createdAt = System.currentTimeMillis()))
        }
    }

    fun clearLibrary() {
        viewModelScope.launch(Dispatchers.IO) { dao.clearTemplates() }
        _state.update { it.copy(message = "Библиотека образцов очищена") }
    }

    // ---------- События ----------

    fun addEventAtCurrent(cardId: String) {
        val id = nextLocalId++
        _state.update {
            val e = PlayEvent(id, it.positionVideoSec, listOf(CardCandidate(cardId, 1f)), confirmed = true)
            it.copy(events = (it.events + e).sortedBy { ev -> ev.timeSec }, unsavedChanges = true)
        }
    }

    /** Исправить карту одного розыгрыша (стоимость из повтора сохраняется, если была). */
    fun replaceEventCard(eventId: Long, cardId: String) {
        _state.update {
            it.copy(
                events = it.events.map { e ->
                    if (e.id == eventId) e.copy(candidates = listOf(CardCandidate(cardId, 1f)), confirmed = true, method = IdMethod.MANUAL) else e
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
        analysisJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { frameSource.close() }
        _state.value = MatchUiState()
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            val title = s.title.ifBlank {
                "Матч от " + java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(java.util.Date())
            }
            val entity = MatchEntity(
                id = s.matchId ?: 0L,
                title = title,
                createdAt = System.currentTimeMillis(),
                videoUri = s.videoUri?.toString(),
                videoDurationSec = s.videoDurationSec,
                matchStartVideoSec = s.matchStartVideoSec,
                eventCount = s.events.size,
                elixirSeries = encodeSeries(s.elixirSeries),
                startElixir = s.startElixir,
            )
            val id = withContext(Dispatchers.IO) { dao.saveMatch(entity, s.events, s.clusters) }
            _state.update { it.copy(matchId = id, title = title, unsavedChanges = false, message = "Матч сохранён") }
        }
    }

    fun load(matchId: Long) {
        analysisJob?.cancel()
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { dao.getMatch(matchId) } ?: return@launch
            val events = withContext(Dispatchers.IO) { dao.getEvents(matchId) }.map { it.toModel() }
            val clusters = withContext(Dispatchers.IO) { dao.getClusters(matchId) }.map { it.toModel() }
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
                clusters = clusters, thumbnails = decodeThumbs(clusters),
                elixirSeries = decodeSeries(m.elixirSeries), startElixir = m.startElixir,
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

    private fun encodeSeries(list: List<ElixirSample>): String? =
        if (list.isEmpty()) null
        else list.joinToString(";") { String.format(Locale.US, "%.2f:%.2f", it.videoTimeSec, it.elixir) }

    private fun decodeSeries(s: String?): List<ElixirSample> =
        s?.split(';')?.mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val t = part.substring(0, i).toFloatOrNull() ?: return@mapNotNull null
            val e = part.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
            ElixirSample(t, e)
        } ?: emptyList()
}
