package com.clashassistant.vision.replay

import com.clashassistant.model.IdMethod
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Признаки одного кадра видео. */
class FrameFeature(
    val timeSec: Float,
    /** Доля «фиолетовых» пикселей в капле эликсира соперника; 0, если панели повтора нет на экране. */
    val panel: Float,
    /** Эликсир соперника по полоске (0..10, с дробной частью). */
    val elixir: Float,
    /** Нормированный вектор картинки карты «След.» соперника. */
    val next: FloatArray,
    /** Доля фиолетового в значке стоимости карты «След.»: ~0 — карта серая (сразу после розыгрыша), >0.05 — цветная. */
    val nextColor: Float = 1f,
)

/** Отрезок времени, когда в «След.» стабильно видна одна картинка. */
data class NextRun(val startSec: Float, val endSec: Float, val cluster: Int, val colored: Boolean)

data class ElixirSample(val videoTimeSec: Float, val elixir: Float)

class ClusterInfo(
    val id: Int,
    val prototype: FloatArray,
    /** Момент видео, где карта лучше всего видна, — для миниатюры. */
    val representativeTimeSec: Float,
    val frames: Int,
)

data class DetectedPlay(
    val videoTimeSec: Float,
    /** Стоимость по падению эликсира; null — падение не было видно (например, полоску закрыло уведомление). */
    val cost: Int?,
    val rawCost: Float?,
    val elixirBefore: Float?,
    val elixirAfter: Float?,
    /** Карты-кандидаты: одна — определена точно, несколько — неоднозначно, пусто — неизвестно. */
    val clusterCandidates: List<Int>,
    val method: IdMethod,
    /** Розыгрыш найден только по смене карты «След.» (без падения эликсира). */
    val virtual: Boolean = false,
)

data class ReplayDetection(
    val matchStartVideoSec: Float,
    val panelFirstSec: Float,
    val panelLastSec: Float,
    val plays: List<DetectedPlay>,
    val runs: List<NextRun>,
    val clusters: List<ClusterInfo>,
    /** Стоимость карты, если она одинакова во всех розыгрышах. */
    val clusterCosts: Map<Int, Int>,
    /** Карты с меняющейся стоимостью (например, Зеркало). */
    val variableCostClusters: Set<Int>,
    val elixirSeries: List<ElixirSample>,
    /** Эликсир соперника в момент 3:00 по данным повтора (если удалось оценить). */
    val startElixir: Float?,
)

/**
 * Распознавание розыгрышей соперника в ПОВТОРЕ Clash Royale (вид, где видна рука соперника).
 *
 * 1. Розыгрыш = резкое падение эликсира соперника; стоимость = величина падения + восстановление.
 * 2. Карта «След.» меняется ТОЛЬКО при розыгрыше. Поэтому всё, что видно в «След.» между двумя
 *    розыгрышами, — одна карта: серая фаза сразу после розыгрыша и цветная после неё объединяются.
 *    Если же между падениями эликсира «След.» сменилась на другую цветную карту — это розыгрыш,
 *    падение которого не было видно (например, полоску закрыло уведомление).
 * 3. Эликсир потрачен, а «След.» не изменилась — способность (чемпиона и т.п.), а не карта.
 * 4. По правилу цикла карта, сыгранная j-й, появляется в «След.» после розыгрыша j+3.
 *    Последние 3 розыгрыша определяются по составу руки и стоимости.
 *
 * Все окна заданы в секундах — алгоритм не зависит от частоты кадров записи.
 * Проверено на 4 реальных повторах (тест ReplayDetectorTest): 68 из 68 розыгрышей.
 */
object ReplayDetector {
    const val PANEL_MIN = 0.08f
    private const val VALID_WIN = 0.7f
    private const val MED_WIN = 0.1f
    private const val DROP_WIN = 0.6f
    private const val DROP_MIN = 0.7f
    private const val BEFORE_WIN = 0.15f
    private const val AFTER_DT = 0.25f
    private const val STABLE_DT = 0.12f
    private const val STABLE_CORR = 0.92f
    const val CLUSTER_CORR = 0.85f
    private const val RUN_GAP = 1.0f
    private const val RUN_MIN = 0.25f
    const val COLORED_MIN = 0.05f
    private const val SEG_TOL = 0.3f
    private const val FADE_GAP = 0.8f
    /** Панель повтора появляется примерно через 2,86 с после 3:00 (замерено по таймеру). */
    const val PANEL_TO_START = 2.86f
    private const val SEC_PER_ELIXIR = 2.8f
    private const val DOUBLE_AT = 120f

    fun detect(frames: List<FrameFeature>): ReplayDetection? {
        val n = frames.size
        if (n < 10) return null
        val t = FloatArray(n) { frames[it].timeSec }
        val on = BooleanArray(n) { frames[it].panel > PANEL_MIN }
        val first = on.indexOfFirst { it }
        if (first < 0) return null
        val last = on.indexOfLast { it }
        val start = t[first] - PANEL_TO_START

        // Кадр «надёжен», если панель видна весь интервал ±VALID_WIN.
        val offPrefix = IntArray(n + 1)
        for (i in 0 until n) offPrefix[i + 1] = offPrefix[i] + if (on[i]) 0 else 1
        val valid = BooleanArray(n) { i ->
            offPrefix[upperBound(t, t[i] + VALID_WIN)] - offPrefix[lowerBound(t, t[i] - VALID_WIN)] == 0
        }
        if (valid.none { it }) return null

        val em = FloatArray(n) { i -> median(frames, lowerBound(t, t[i] - MED_WIN), upperBound(t, t[i] + MED_WIN)) }
        val drops = findDrops(t, em, valid, start)
        val cl = clusterNext(frames, t, valid)
        val uf = UnionFind(cl.protos.size)

        // ---- Отрезки между падениями эликсира ----
        val segs = List(drops.size + 1) { ArrayList<NextRun>() }
        for (r in cl.runs) {
            val k = drops.count { r.startSec >= it.t - SEG_TOL }
            segs[k] += r
        }
        // Серая фаза -> цветная в одном отрезке = одна карта
        for (sg in segs) {
            for (q in 1 until sg.size) {
                val a = sg[q - 1]; val b = sg[q]
                if (!a.colored && b.colored && b.startSec - a.endSec < FADE_GAP) uf.union(a.cluster, b.cluster)
            }
        }
        fun state(sg: List<NextRun>): Int? {
            val col = sg.lastOrNull { it.colored }
            return when {
                col != null -> uf.find(col.cluster)
                sg.isNotEmpty() -> uf.find(sg.last().cluster)
                else -> null
            }
        }

        // ---- События: падения + пропущенные розыгрыши ----
        class Ev(val t: Float, val drop: Drop?, val state: Int?)
        val events = ArrayList<Ev>()
        var s0: Int? = null
        for ((k, sg) in segs.withIndex()) {
            val colored = sg.filter { it.colored }
            val firstState = colored.firstOrNull()?.let { uf.find(it.cluster) } ?: state(sg)
            if (k == 0) s0 = firstState
            else events += Ev(drops[k - 1].t, drops[k - 1], firstState)
            for (q in 1 until colored.size) {
                val b = colored[q]
                if (uf.find(colored[q - 1].cluster) != uf.find(b.cluster)) events += Ev(b.startSec, null, uf.find(b.cluster))
            }
        }

        // ---- Способности: эликсир потрачен, «След.» не изменилась ----
        val plays = ArrayList<Ev>()
        val abilities = ArrayList<Ev>()
        var prev = s0
        for (e in events) {
            if (e.drop != null && e.state != null && prev != null && e.state == prev) abilities += e
            else {
                plays += e
                if (e.state != null) prev = e.state
            }
        }

        // ---- Цикл: карта j-го розыгрыша = «След.» после розыгрыша j+3 ----
        val m = plays.size
        val x = arrayOfNulls<List<Int>>(m)
        val method = arrayOfNulls<IdMethod>(m)
        for (j in 0 until m) {
            val st = if (j + 3 < m) plays[j + 3].state else null
            if (st != null) { x[j] = listOf(st); method[j] = IdMethod.CYCLE }
        }
        val costLists = HashMap<Int, MutableList<Int>>()
        for (j in 0 until m) {
            val c = x[j]?.singleOrNull() ?: continue
            val cost = plays[j].drop?.cost ?: continue
            costLists.getOrPut(c) { mutableListOf() } += cost
        }
        val costs = HashMap<Int, Int>()
        val variable = HashSet<Int>()
        for ((c, list) in costLists) if (list.toSet().size == 1) costs[c] = list[0] else variable += c
        val deck = cl.runs.map { uf.find(it.cluster) }.toSet()

        for (j in 0 until m) {
            if (x[j] != null) continue
            val queue = HashSet<Int>()
            (if (j >= 1) plays[j - 1].state else s0)?.let { queue += it }
            for (q in (j - 3) until j) if (q >= 0) x[q]?.singleOrNull()?.let { queue += it }
            val hand = deck - queue
            val obs = plays[j].drop?.cost
            val prevCost = if (j >= 1) plays[j - 1].drop?.cost else null
            var cand: List<Int> = if (obs == null) hand.toList() else hand.filter { c ->
                costs[c] == obs || (c in variable && prevCost != null && obs == prevCost + 1)
            }
            if (cand.isEmpty() && obs != null) cand = hand.filter { it !in costs && it !in variable }
            x[j] = cand.sorted()
            method[j] = if (cand.isEmpty()) IdMethod.UNKNOWN else IdMethod.HAND
        }

        // ---- Перенумерация карт по порядку появления ----
        val order = cl.runs.sortedBy { it.startSec }.map { uf.find(it.cluster) }.distinct()
        val remap = order.withIndex().associate { (i, c) -> c to i }
        fun re(c: Int) = remap[uf.find(c)] ?: -1

        val out = ArrayList<DetectedPlay>()
        for ((j, e) in plays.withIndex()) {
            val cands = (x[j] ?: emptyList()).map { re(it) }
            val d = e.drop
            val cost = d?.cost ?: cands.singleOrNull()?.let { c -> costs.entries.firstOrNull { re(it.key) == c }?.value }
            out += DetectedPlay(e.t, cost, d?.raw, d?.before, d?.after, cands, method[j] ?: IdMethod.UNKNOWN, virtual = d == null)
        }
        for (e in abilities) {
            val d = e.drop!!
            out += DetectedPlay(e.t, d.cost, d.raw, d.before, d.after, emptyList(), IdMethod.ABILITY)
        }
        out.sortBy { it.videoTimeSec }

        // ---- Карты соперника: прототип и миниатюра по самой «цветной» группе ----
        val clusters = order.map { root ->
            val members = cl.protos.indices.filter { uf.find(it) == root }
            val colRuns = cl.runs.filter { uf.find(it.cluster) == root && it.colored }
            val best = members.maxByOrNull { mbr -> colRuns.filter { it.cluster == mbr }.sumOf { (it.endSec - it.startSec).toDouble() } } ?: root
            val rep = (colRuns.ifEmpty { cl.runs.filter { uf.find(it.cluster) == root } })
                .maxByOrNull { it.endSec - it.startSec }!!
            ClusterInfo(remap.getValue(root), cl.protos[best], (rep.startSec + rep.endSec) / 2f, members.sumOf { cl.counts[it] })
        }

        // ---- Фактический эликсир и стартовый эликсир ----
        val series = ArrayList<ElixirSample>()
        var nextSample = t[first]
        for (i in 0 until n) {
            if (valid[i] && t[i] >= nextSample) {
                series += ElixirSample(t[i], em[i])
                nextSample = t[i] + 0.5f
            }
        }
        val firstDropT = drops.firstOrNull()?.t ?: Float.MAX_VALUE
        val startElixir = series.firstOrNull { it.videoTimeSec < firstDropT && it.elixir < 9.9f }?.let {
            (it.elixir - (it.videoTimeSec - start) / SEC_PER_ELIXIR).coerceIn(0f, 10f)
        }

        return ReplayDetection(
            matchStartVideoSec = start,
            panelFirstSec = t[first],
            panelLastSec = t[last],
            plays = out,
            runs = cl.runs.map { it.copy(cluster = re(it.cluster)) },
            clusters = clusters,
            clusterCosts = costs.mapKeys { re(it.key) },
            variableCostClusters = variable.map { re(it) }.toSet(),
            elixirSeries = series,
            startElixir = startElixir,
        )
    }

    // ---------- 1. Падения эликсира ----------

    private class Drop(val t: Float, val cost: Int, val raw: Float, val before: Float, val after: Float)

    private fun findDrops(t: FloatArray, em: FloatArray, valid: BooleanArray, start: Float): List<Drop> {
        val n = t.size
        val drops = ArrayList<Drop>()
        var i = 0
        while (i < n - 1) {
            if (!valid[i]) { i++; continue }
            var j = i + 1
            while (j < n && t[j] - t[i] <= DROP_WIN && valid[j]) j++
            if (j - i > 1) {
                var k = i
                for (q in i until j) if (em[q] < em[k]) k = q
                if (em[i] - em[k] > DROP_MIN) {
                    val b0 = lowerBound(t, t[i] - BEFORE_WIN)
                    var before = em[b0]
                    for (q in b0..i) before = max(before, em[q])
                    val a = min(n - 1, lowerBound(t, t[k] + AFTER_DT))
                    val after = em[a]
                    val rate = (1f / SEC_PER_ELIXIR) * (if (t[i] - start >= DOUBLE_AT) 2f else 1f)
                    val raw = before - after + rate * (t[a] - t[i])
                    // Момент розыгрыша — когда эликсир упал на половину порога.
                    var mIdx = i
                    while (mIdx < k && em[mIdx] > em[i] - 0.5f) mIdx++
                    drops += Drop(t[mIdx], floor(raw + 0.5f).toInt(), raw, before, after)
                    i = a + 1
                    continue
                }
            }
            i++
        }
        return drops
    }

    // ---------- 2. Кластеры карты «След.» ----------

    private class Clustering(val protos: List<FloatArray>, val counts: List<Int>, val runs: List<NextRun>)

    private fun clusterNext(frames: List<FrameFeature>, t: FloatArray, valid: BooleanArray): Clustering {
        val n = t.size
        val protos = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        val lab = IntArray(n) { -1 }
        for (i in 0 until n) {
            if (!valid[i]) continue
            val p = upperBound(t, t[i] - STABLE_DT) - 1
            val q = lowerBound(t, t[i] + STABLE_DT)
            if (p < 0 || q >= n) continue
            val v = frames[i].next
            if (dot(v, frames[p].next) < STABLE_CORR || dot(v, frames[q].next) < STABLE_CORR) continue
            var best = -1
            var bestSim = -2f
            for (c in protos.indices) {
                val s = dot(v, protos[c])
                if (s > bestSim) { bestSim = s; best = c }
            }
            val c: Int
            if (best >= 0 && bestSim >= CLUSTER_CORR) {
                c = best
                val pr = protos[c]
                for (d in pr.indices) pr[d] = pr[d] * 0.95f + v[d] * 0.05f
                normalize(pr)
                counts[c] = counts[c] + 1
            } else {
                protos += v.copyOf()
                counts += 1
                c = protos.size - 1
            }
            lab[i] = c
        }

        // Отрезки: [t0, t1, cluster, цветных кадров, всего кадров]
        class Raw(val t0: Float, var t1: Float, val c: Int, var col: Int, var tot: Int)
        val raw = ArrayList<Raw>()
        for (i in 0 until n) {
            if (lab[i] < 0) continue
            val colored = if (frames[i].nextColor > COLORED_MIN) 1 else 0
            val r = raw.lastOrNull()
            if (r != null && r.c == lab[i] && t[i] - r.t1 < RUN_GAP) { r.t1 = t[i]; r.col += colored; r.tot++ }
            else raw += Raw(t[i], t[i], lab[i], colored, 1)
        }
        val merged = ArrayList<Raw>()
        for (r in raw) {
            if (r.t1 - r.t0 < RUN_MIN) continue
            val p = merged.lastOrNull()
            if (p != null && p.c == r.c && r.t0 - p.t1 < RUN_GAP) { p.t1 = r.t1; p.col += r.col; p.tot += r.tot }
            else merged += r
        }
        val runs = merged.map { NextRun(it.t0, it.t1, it.c, it.col * 2 > it.tot) }
        return Clustering(protos, counts, runs)
    }

    private class UnionFind(size: Int) {
        private val p = IntArray(size) { it }
        fun find(x: Int): Int {
            var a = x
            while (p[a] != a) { p[a] = p[p[a]]; a = p[a] }
            return a
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) p[max(ra, rb)] = min(ra, rb)
        }
    }

    // ---------- Вспомогательное ----------

    /** Первый индекс с a[i] >= x. */
    fun lowerBound(a: FloatArray, x: Float): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Первый индекс с a[i] > x. */
    fun upperBound(a: FloatArray, x: Float): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] <= x) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun median(frames: List<FrameFeature>, from: Int, to: Int): Float {
        val k = to - from
        if (k <= 0) return frames[from.coerceAtMost(frames.size - 1)].elixir
        val buf = FloatArray(k) { frames[from + it].elixir }
        buf.sort()
        return if (k % 2 == 1) buf[k / 2] else (buf[k / 2 - 1] + buf[k / 2]) / 2f
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s) + 1e-6f
        for (i in v.indices) v[i] /= norm
    }
}
